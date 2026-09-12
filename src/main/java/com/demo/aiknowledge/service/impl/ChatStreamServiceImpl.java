package com.demo.aiknowledge.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.demo.aiknowledge.common.ErrorCode;
import com.demo.aiknowledge.entity.Conversation;
import com.demo.aiknowledge.entity.Message;
import com.demo.aiknowledge.exception.BusinessException;
import com.demo.aiknowledge.mapper.ConversationMapper;
import com.demo.aiknowledge.mapper.MessageMapper;
import com.demo.aiknowledge.service.AiService;
import com.demo.aiknowledge.service.ChatPersistenceService;
import com.demo.aiknowledge.service.ChatStreamService;
import com.demo.aiknowledge.service.ConversationContextService;
import com.demo.aiknowledge.service.QaUnansweredService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 聊天流式回答服务实现
 *
 * <p>线程模型: 请求线程只做"校验 + tx1 落库 + 返回 emitter", 真正的转发在独立线程执行。
 * 原因: SSE 是长连接（一次回答可能十几秒）, 若在请求线程同步等待, Tomcat 线程会被占满,
 * 并发几个用户就会拒绝服务。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ChatStreamServiceImpl implements ChatStreamService {

    private final ConversationMapper conversationMapper;
    private final MessageMapper messageMapper;
    private final ChatPersistenceService chatPersistenceService;
    private final ConversationContextService conversationContextService;
    private final AiService aiService;
    private final QaUnansweredService qaUnansweredService;
    private final ObjectMapper objectMapper;
    /** Spring Boot 自动配置的 applicationTaskExecutor */
    private final TaskExecutor taskExecutor;

    /** SSE 连接超时: 取 5 分钟（LLM 生成可能十余秒, 默认超时会掐断长回答） */
    private static final long SSE_TIMEOUT_MS = 5 * 60 * 1000L;

    @Override
    public SseEmitter streamAnswer(Long userId, Long conversationId, String content) {
        // 1. 归属校验: conversationId 由前端传入可被伪造 —— 只能对自己的会话发起流式问答
        Conversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAMS, "会话不存在");
        }
        if (!conversation.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }

        // 2. tx1: 用户消息先落库并提交 —— 生成失败/断开也不会丢用户输入
        chatPersistenceService.saveUserMessage(conversationId, userId, content);

        // 3. 首条消息触发标题生成（@Async, 与同步链路行为一致）
        Long msgCount = messageMapper.selectCount(new LambdaQueryWrapper<Message>()
                .eq(Message::getConversationId, conversationId));
        if (msgCount == 1) {
            aiService.generateTitle(conversationId, content);
        }

        // 4. 构建 SSE 并交由独立线程执行转发
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        taskExecutor.execute(() -> doStream(emitter, userId, conversationId, content));
        return emitter;
    }

    /**
     * 转发 Python 事件流并在结束后落库。
     */
    private void doStream(SseEmitter emitter, Long userId, Long conversationId, String content) {
        // 上下文 = 最近10条消息（含刚提交的用户消息）, 与同步链路一致
        String conversationContext = buildConversationContext(conversationId);

        // 累积区: token 拼接 / 完整回答(end 事件, 更可靠) / 引用 / 任务类型
        StringBuilder answerBuffer = new StringBuilder();
        AtomicReference<String> sourcesJsonRef = new AtomicReference<>(null);
        AtomicReference<String> taskTypeRef = new AtomicReference<>("unknown");
        // 客户端是否已断开（用户点"停止"或关页面）: 断开后停止转发, 但保留已生成内容用于落库
        AtomicBoolean clientGone = new AtomicBoolean(false);
        AtomicBoolean persisted = new AtomicBoolean(false);

        try {
            aiService.askStream(content, conversationContext, userId, conversationId, event -> {
                if (clientGone.get()) {
                    return; // 客户端已断开, 无需再转发（但仍继续累积, 供断线保存）
                }
                String type = String.valueOf(event.get("type"));

                // ---- 1) 累积: 供流结束后落库 / 断线保存 ----
                switch (type) {
                    case "token":
                        answerBuffer.append(event.getOrDefault("content", ""));
                        break;
                    case "end":
                        // end 事件携带完整回答, 以其为准（比逐 token 拼接更可靠）
                        answerBuffer.setLength(0);
                        answerBuffer.append(event.getOrDefault("content", ""));
                        break;
                    case "sources":
                        try {
                            if (event.get("sources") != null) {
                                sourcesJsonRef.set(objectMapper.writeValueAsString(event.get("sources")));
                            }
                        } catch (Exception e) {
                            log.warn("序列化 sources 失败: {}", e.getMessage());
                        }
                        if (event.get("task_type") != null) {
                            taskTypeRef.set(String.valueOf(event.get("task_type")));
                        }
                        break;
                    default:
                        break; // routed / start 等仅转发
                }

                // ---- 2) 实时转发给浏览器 ----
                try {
                    emitter.send(SseEmitter.event()
                            .data(objectMapper.writeValueAsString(event)));
                } catch (Exception sendEx) {
                    // 发送失败通常意味着客户端断开（IO 异常）: 标记后停止转发
                    log.info("SSE 客户端已断开, 停止转发 - conversationId: {}", conversationId);
                    clientGone.set(true);
                }
            });

            // ---- 3) 流结束: 落库（完整回答或已生成的部分内容）----
            Message saved = persistAnswer(userId, conversationId, content,
                    answerBuffer.toString(), sourcesJsonRef.get(), taskTypeRef.get());
            persisted.set(true);

            // ---- 4) 通知前端"已保存"（携带真实 messageId, 供前端替换临时 id）并关闭 ----
            if (!clientGone.get()) {
                try {
                    emitter.send(SseEmitter.event().data(objectMapper.writeValueAsString(
                            Map.of("type", "saved",
                                   "messageId", saved.getId() == null ? 0 : saved.getId()))));
                    emitter.complete();
                } catch (Exception e) {
                    log.info("SSE 收尾发送失败（客户端可能已断开）: {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("流式回答失败 - conversationId: {}, error: {}", conversationId, e.getMessage(), e);
            // 异常时仍保存已生成内容: 用户已经在屏幕上看到的内容, 不应因异常在刷新后消失
            if (!persisted.get()) {
                try {
                    persistAnswer(userId, conversationId, content,
                            answerBuffer.toString(), sourcesJsonRef.get(), taskTypeRef.get());
                } catch (Exception saveEx) {
                    log.error("异常路径保存部分回答失败: {}", saveEx.getMessage());
                }
            }
            emitter.completeWithError(e);
        }
    }

    /**
     * 落库回答（tx2）。空内容不落库（避免产生空白消息）。
     *
     * @return 已保存的消息; 内容为空时返回空 Message 对象（不落库）
     */
    private Message persistAnswer(Long userId, Long conversationId, String question,
                                  String answer, String sourcesJson, String taskType) {
        if (answer == null || answer.isEmpty()) {
            log.info("流式回答为空, 跳过落库 - conversationId: {}", conversationId);
            return new Message();
        }
        // 与同步链路保持一致的兜底记录逻辑
        if (answer.contains("抱歉") || answer.contains("无法回答")) {
            qaUnansweredService.recordUnansweredQuestion(question);
        }
        return chatPersistenceService.saveAssistantMessage(
                conversationId, userId, question, answer, sourcesJson, taskType);
    }

    /** 构建对话上下文字符串（与 ChatServiceImpl 同步链路一致: 最近10条 role: content） */
    private String buildConversationContext(Long conversationId) {
        List<Message> messages = conversationContextService.getConversationContext(conversationId, 10);
        StringBuilder sb = new StringBuilder();
        for (Message msg : messages) {
            sb.append(msg.getRole()).append(": ").append(msg.getContent()).append("\n");
        }
        return sb.toString();
    }
}
