package com.demo.aiknowledge.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.demo.aiknowledge.common.ErrorCode;
import com.demo.aiknowledge.common.SecurityUtils;
import com.demo.aiknowledge.dto.AiResponse;
import com.demo.aiknowledge.entity.AdminConversation;
import com.demo.aiknowledge.entity.AdminMessage;
import com.demo.aiknowledge.exception.BusinessException;
import com.demo.aiknowledge.mapper.AdminConversationMapper;
import com.demo.aiknowledge.mapper.AdminMessageMapper;
import com.demo.aiknowledge.service.AdminChatPersistenceService;
import com.demo.aiknowledge.service.AdminChatService;
import com.demo.aiknowledge.service.AiService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Service
@Slf4j
@RequiredArgsConstructor
public class AdminChatServiceImpl implements AdminChatService {

    private final AdminConversationMapper adminConversationMapper;
    private final AdminMessageMapper adminMessageMapper;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final AiService aiService;
    /** 短事务持久化服务（拆分事务边界, 详见其接口文档） */
    private final AdminChatPersistenceService adminChatPersistenceService;
    /** Spring Boot 自动配置的 applicationTaskExecutor: SSE 转发在独立线程执行, 不占用请求线程 */
    private final TaskExecutor taskExecutor;

    /** SSE 连接超时: 取 5 分钟（LLM 生成可能十余秒, 默认超时会掐断长回答） */
    private static final long SSE_TIMEOUT_MS = 5 * 60 * 1000L;

    @Override
    public AdminConversation createConversation(Long adminId, String title) {
        AdminConversation conversation = new AdminConversation();
        conversation.setAdminId(adminId);
        conversation.setTitle(title != null ? title : "新建会话 " + LocalDateTime.now());
        conversation.setCreateTime(LocalDateTime.now());
        conversation.setIsPinned(false);
        adminConversationMapper.insert(conversation);
        return conversation;
    }

    @Override
    public List<AdminConversation> getHistory(Long adminId) {
        return adminConversationMapper.selectList(new LambdaQueryWrapper<AdminConversation>()
                .eq(AdminConversation::getAdminId, adminId)
                .orderByDesc(AdminConversation::getIsPinned)
                .orderByDesc(AdminConversation::getCreateTime));
    }

    @Override
    public AdminConversation updateConversation(Long conversationId, String title, Boolean isPinned) {
        AdminConversation conversation = adminConversationMapper.selectById(conversationId);
        if (conversation == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAMS, "会话不存在");
        }
        // 归属校验: 只能操作自己的会话
        SecurityUtils.checkOwnership(conversation.getAdminId());

        if (title != null) {
            conversation.setTitle(title);
        }
        if (isPinned != null) {
            conversation.setIsPinned(isPinned);
        }
        adminConversationMapper.updateById(conversation);
        return conversation;
    }

    @Override
    public AdminMessage sendMessage(Long adminId, Long conversationId, String content) {
        AdminConversation conversation = adminConversationMapper.selectById(conversationId);
        if (conversation == null || !conversation.getAdminId().equals(adminId)) {
            throw new RuntimeException("会话不存在或无权访问");
        }

        // 1. 短事务①: 管理员消息先落库并立即提交（AI 失败也不丢输入）
        adminChatPersistenceService.saveUserMessage(conversationId, content);

        String context = buildContext(conversationId);

        // 2. 调用 AI 管理助手 —— 在事务之外执行（HTTP, 数秒）
        AiResponse aiResponse;
        try {
            aiResponse = callAdminAgent(content, context, adminId);
        } catch (Exception e) {
            log.error("AI 管理助手调用失败, 管理员消息已持久化 - conversationId: {}, error: {}",
                    conversationId, e.getMessage());
            throw e;
        }

        String answer = aiResponse.getAnswer();
        String sourcesJson = null;
        String taskType = aiResponse.getTaskType();

        if (aiResponse.getSources() != null && !aiResponse.getSources().isEmpty()) {
            try {
                sourcesJson = objectMapper.writeValueAsString(aiResponse.getSources());
            } catch (Exception e) {
                log.error("Failed to serialize sources", e);
            }
        }

        // 3. 短事务②: 保存回答 + 按需更新会话标题
        return adminChatPersistenceService.saveAssistantMessage(
                conversation, content, answer, sourcesJson, taskType);
    }

    @Override
    public SseEmitter streamAnswer(Long adminId, Long conversationId, String content) {
        // 1. 归属校验: 只能对自己的管理端会话发起流式问答
        AdminConversation conversation = adminConversationMapper.selectById(conversationId);
        if (conversation == null || !conversation.getAdminId().equals(adminId)) {
            throw new BusinessException(ErrorCode.INVALID_PARAMS, "会话不存在或无权访问");
        }

        // 2. tx1: 管理员消息先落库并提交 —— 生成失败/断开也不会丢输入
        adminChatPersistenceService.saveUserMessage(conversationId, content);
        String context = buildContext(conversationId);

        // 3. 构建 SSE 并交由独立线程转发（请求线程立即释放）
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        taskExecutor.execute(() -> doStream(emitter, conversation, content, context));
        return emitter;
    }

    /**
     * 转发 Python 事件流并在结束后落库。
     *
     * <p>与用户端 ChatStreamServiceImpl 同构: 客户端断开后停止转发, 但继续累积内容,
     * 保证"用户已看到的内容"刷新后不会消失。
     */
    private void doStream(SseEmitter emitter, AdminConversation conversation,
                          String question, String context) {
        StringBuilder answerBuffer = new StringBuilder();
        AtomicReference<String> sourcesJsonRef = new AtomicReference<>(null);
        AtomicReference<String> taskTypeRef = new AtomicReference<>("admin_copilot");
        AtomicBoolean clientGone = new AtomicBoolean(false);
        AtomicBoolean persisted = new AtomicBoolean(false);

        try {
            aiService.askStreamForAdmin(question, context, conversation.getAdminId(), event -> {
                if (clientGone.get()) {
                    return; // 客户端已断开, 无需再转发（但仍继续累积, 供断线保存）
                }
                String type = String.valueOf(event.get("type"));

                // ---- 1) 累积: 供流结束后落库 / 断线保存 ----
                switch (type) {
                    case "routed":
                        // 记录路由结果: 部分链路(如闲聊)不发 sources 事件,
                        // 若只靠 sources 取 task_type, 落库会一直是默认值"admin_copilot"
                        if (event.get("task_type") != null) {
                            taskTypeRef.set(String.valueOf(event.get("task_type")));
                        }
                        break;
                    case "token":
                        answerBuffer.append(event.getOrDefault("content", ""));
                        break;
                    case "end":
                        // end 事件携带完整回答, 以其为准（比逐 token 拼接更可靠）。
                        // content 可能是字符串, 也可能是对象 {answer, sources, task_type}——
                        // 后者直接拼接会把 Map.toString() 落库（历史 bug）
                        answerBuffer.setLength(0);
                        Object endContent = event.get("content");
                        if (endContent instanceof Map<?, ?> endMap) {
                            Object endAnswer = endMap.get("answer");
                            answerBuffer.append(endAnswer == null ? "" : String.valueOf(endAnswer));
                            if (endMap.get("sources") != null) {
                                try {
                                    sourcesJsonRef.set(objectMapper.writeValueAsString(endMap.get("sources")));
                                } catch (Exception e) {
                                    log.warn("序列化 end.sources 失败: {}", e.getMessage());
                                }
                            }
                            if (endMap.get("task_type") != null) {
                                taskTypeRef.set(String.valueOf(endMap.get("task_type")));
                            }
                        } else if (endContent != null) {
                            answerBuffer.append(endContent);
                        }
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
                        break; // routed 等仅转发
                }

                // ---- 2) 实时转发给浏览器 ----
                try {
                    emitter.send(SseEmitter.event()
                            .data(objectMapper.writeValueAsString(event)));
                } catch (Exception sendEx) {
                    log.info("管理端 SSE 客户端已断开, 停止转发 - conversationId: {}", conversation.getId());
                    clientGone.set(true);
                }
            });

            // ---- 3) 流结束: 落库（完整回答或已生成的部分内容）----
            AdminMessage saved = persistAnswer(conversation, question, answerBuffer.toString(),
                    sourcesJsonRef.get(), taskTypeRef.get());
            persisted.set(true);

            // ---- 4) 通知前端"已保存"（携带真实 messageId）并关闭 ----
            if (!clientGone.get()) {
                try {
                    emitter.send(SseEmitter.event().data(objectMapper.writeValueAsString(
                            Map.of("type", "saved",
                                   "messageId", saved.getId() == null ? 0 : saved.getId()))));
                    emitter.complete();
                } catch (Exception e) {
                    log.info("管理端 SSE 收尾发送失败（客户端可能已断开）: {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("管理端流式回答失败 - conversationId: {}, error: {}",
                    conversation.getId(), e.getMessage(), e);
            // 异常时仍保存已生成内容: 用户已经看到的内容不应因异常在刷新后消失
            if (!persisted.get()) {
                try {
                    persistAnswer(conversation, question, answerBuffer.toString(),
                            sourcesJsonRef.get(), taskTypeRef.get());
                } catch (Exception saveEx) {
                    log.error("异常路径保存部分回答失败: {}", saveEx.getMessage());
                }
            }
            emitter.completeWithError(e);
        }
    }

    /** 落库回答（tx2）。空内容不落库（避免产生空白消息）。 */
    private AdminMessage persistAnswer(AdminConversation conversation, String question, String answer,
                                       String sourcesJson, String taskType) {
        if (answer == null || answer.isEmpty()) {
            log.info("管理端流式回答为空, 跳过落库 - conversationId: {}", conversation.getId());
            return new AdminMessage();
        }
        return adminChatPersistenceService.saveAssistantMessage(
                conversation, question, answer, sourcesJson, taskType);
    }

    @Override
    public List<AdminMessage> getMessages(Long conversationId) {
        // 归属校验: 禁止通过遍历 conversationId 读取他人的管理助手会话
        AdminConversation conversation = adminConversationMapper.selectById(conversationId);
        if (conversation == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAMS, "会话不存在");
        }
        SecurityUtils.checkOwnership(conversation.getAdminId());

        return adminMessageMapper.selectList(new LambdaQueryWrapper<AdminMessage>()
                .eq(AdminMessage::getConversationId, conversationId)
                .orderByAsc(AdminMessage::getCreateTime));
    }

    @Override
    @Transactional
    public void deleteConversation(Long conversationId) {
        // 归属校验: 只能删除自己的会话
        AdminConversation conversation = adminConversationMapper.selectById(conversationId);
        if (conversation == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAMS, "会话不存在");
        }
        SecurityUtils.checkOwnership(conversation.getAdminId());

        adminMessageMapper.delete(new LambdaQueryWrapper<AdminMessage>()
                .eq(AdminMessage::getConversationId, conversationId));
        adminConversationMapper.deleteById(conversationId);
    }

    @Override
    @Transactional
    public AdminMessage submitFeedback(Long messageId, String feedbackType) {
        AdminMessage message = adminMessageMapper.selectById(messageId);
        if (message == null) {
            throw new RuntimeException("消息不存在");
        }
        // 归属校验: 消息所属会话必须属于当前登录管理员
        AdminConversation conversation = adminConversationMapper.selectById(message.getConversationId());
        if (conversation == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAMS, "会话不存在");
        }
        SecurityUtils.checkOwnership(conversation.getAdminId());

        // 已知缺口（如实记录, 未虚假修复）: admin_message 表没有 feedback 字段,
        // 管理端反馈在数据模型层尚未实现, 因此此处无法真正落库。
        // 原实现会顺手 updateById 刷新 createTime（把"更新时间"写进创建时间, 属误操作）,
        // 这里不再写库, 仅记录日志, 等管理端反馈功能立项时再补字段。
        log.warn("Admin feedback not persisted (admin_message has no feedback column): messageId={}, type={}",
                messageId, feedbackType);
        return message;
    }

    private String buildContext(Long conversationId) {
        List<AdminMessage> messages = getMessages(conversationId);
        StringBuilder sb = new StringBuilder();
        for (AdminMessage msg : messages) {
            sb.append(msg.getRole()).append(": ").append(msg.getContent()).append("\n");
        }
        return sb.toString();
    }

    private AiResponse callAdminAgent(String question, String context, Long adminId) {
        try {
            Map<String, Object> response = aiService.askForAdmin(question, context, adminId);
            AiResponse aiResponse = new AiResponse();
            aiResponse.setAnswer((String) response.get("answer"));
            aiResponse.setTaskType((String) response.get("task_type"));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> sources = (List<Map<String, Object>>) response.get("sources");
            aiResponse.setSources(sources);
            return aiResponse;
        } catch (Exception e) {
            log.error("Failed to call admin agent", e);
            AiResponse aiResponse = new AiResponse();
            aiResponse.setAnswer("抱歉，服务暂时不可用，请稍后再试。");
            aiResponse.setSources(null);
            aiResponse.setTaskType("unknown");
            return aiResponse;
        }
    }
}