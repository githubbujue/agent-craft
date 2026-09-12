package com.demo.aiknowledge.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.demo.aiknowledge.common.ErrorCode;
import com.demo.aiknowledge.common.SecurityUtils;
import com.demo.aiknowledge.config.CacheConfig;
import com.demo.aiknowledge.dto.AiResponse;
import com.demo.aiknowledge.entity.Conversation;
import com.demo.aiknowledge.entity.Message;
import com.demo.aiknowledge.entity.QaLog;
import com.demo.aiknowledge.exception.BusinessException;
import com.demo.aiknowledge.mapper.ConversationMapper;
import com.demo.aiknowledge.mapper.MessageMapper;
import com.demo.aiknowledge.mapper.QaLogMapper;
import com.demo.aiknowledge.service.AiService;
import com.demo.aiknowledge.service.CacheService;
import com.demo.aiknowledge.service.ChatPersistenceService;
import com.demo.aiknowledge.service.ChatService;
import com.demo.aiknowledge.service.ConversationContextService;
import com.demo.aiknowledge.service.QaUnansweredService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class ChatServiceImpl implements ChatService {

    private final ConversationMapper conversationMapper;
    private final MessageMapper messageMapper;
    private final QaLogMapper qaLogMapper;
    private final AiService aiService;
    private final QaUnansweredService qaUnansweredService;
    private final ConversationContextService conversationContextService;
    private final ObjectMapper objectMapper;
    private final CacheService cacheService;
    /** 短事务持久化服务（拆分事务边界, 详见其接口文档） */
    private final ChatPersistenceService chatPersistenceService;

    @Override
    public Conversation createConversation(Long userId, String title) {
        Conversation conversation = new Conversation();
        conversation.setUserId(userId);
        conversation.setTitle(title != null ? title : "New Chat " + LocalDateTime.now());
        conversation.setCreateTime(LocalDateTime.now());
        conversationMapper.insert(conversation);
        return conversation;
    }

    @Override
    public List<Conversation> getHistory(Long userId) {
        return conversationMapper.selectList(new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getUserId, userId)
                .orderByDesc(Conversation::getIsPinned) // 先按置顶排序
                .orderByDesc(Conversation::getCreateTime)); // 再按时间排序
    }

    @Override
    public Conversation updateConversation(Long conversationId, String title, Boolean isPinned) {
        Conversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAMS, "会话不存在");
        }
        // 归属校验: 禁止改名/置顶他人会话
        SecurityUtils.checkOwnership(conversation.getUserId());

        if (title != null) {
            conversation.setTitle(title);
        }
        if (isPinned != null) {
            conversation.setIsPinned(isPinned);
        }
        conversationMapper.updateById(conversation);
        return conversation;
    }

    @Override
    public Message sendMessage(Long userId, Long conversationId, String content) {
        // 0. 归属校验: conversationId 由前端传入, 可被伪造 —— 只能向自己的会话发消息。
        //    userId 由 Controller 从 JWT 提取（可信）, conversation 归属从数据库读取（可信）。
        Conversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAMS, "会话不存在");
        }
        if (!conversation.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }

        // 1. 短事务①: 用户消息先落库并立即提交
        //    即使后续 AI 调用失败, 用户输入也不会丢（不会出现"消息凭空消失"）
        chatPersistenceService.saveUserMessage(conversationId, userId, content);

        // 2. 首条消息 → 异步生成标题（@Async, 不阻塞本线程）
        Long msgCount = messageMapper.selectCount(new LambdaQueryWrapper<Message>()
                .eq(Message::getConversationId, conversationId));
        if (msgCount == 1) { // 明确判断是否为第一条消息
             // 异步生成标题，避免阻塞
             aiService.generateTitle(conversationId, content);
        }

        // 3. 获取对话上下文（获取最近10条消息，包含刚提交的用户消息）
        List<Message> contextMessages = conversationContextService.getConversationContext(conversationId, 10);
        // 构建上下文字符串
        StringBuilder contextBuilder = new StringBuilder();
        for (Message msg : contextMessages) {
            contextBuilder.append(msg.getRole()).append(": ").append(msg.getContent()).append("\n");
        }
        String conversationContext = contextBuilder.toString();
        log.debug("对话上下文构建完成，长度: {}，内容: {}", conversationContext.length(), conversationContext);

        // 4. 调用 AI 服务获取回答 —— 在事务之外执行
        //    此处是 HTTP + LLM 调用(5-15秒), 不再占用数据库连接与行锁;
        //    失败时仅记录日志并向上抛出, 不产生"用户消息被回滚但 Python 侧记忆已写入"的跨系统不一致
        AiResponse aiResponse;
        try {
            aiResponse = aiService.ask(content, conversationContext, userId, conversationId);
        } catch (Exception e) {
            log.error("AI 服务调用失败, 用户消息已持久化 - conversationId: {}, error: {}",
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
        } else {
            // 如果没有 sources 或者 answer 看起来像不知道，记录到 unanswered
            if (answer.contains("抱歉") || answer.contains("无法回答")) {
                 qaUnansweredService.recordUnansweredQuestion(content);
            }
        }

        // 5. 短事务②: 保存 AI 回答 + 更新上下文 + QA 日志
        return chatPersistenceService.saveAssistantMessage(
                conversationId, userId, content, answer, sourcesJson, taskType);
    }

    @Override
    public List<Message> getMessages(Long conversationId) {
        // 归属校验: 禁止通过遍历 conversationId 读取他人会话内容（水平越权）
        Conversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAMS, "会话不存在");
        }
        SecurityUtils.checkOwnership(conversation.getUserId());

        // 使用对话上下文服务获取消息，支持滑动窗口和缓存
        return conversationContextService.getConversationContext(conversationId, 20);
    }

    @Override
    @Transactional
    public void deleteConversation(Long conversationId) {
        // 归属校验: 只能删除自己的会话
        Conversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAMS, "会话不存在");
        }
        SecurityUtils.checkOwnership(conversation.getUserId());

        // 删除会话相关的消息
        messageMapper.delete(new LambdaQueryWrapper<Message>().eq(Message::getConversationId, conversationId));
        // 删除会话本身
        conversationMapper.deleteById(conversationId);
    }

    @Override
    @Transactional
    public Message submitFeedback(Long messageId, String feedbackType) {
        // 1. 查找消息
        Message message = messageMapper.selectById(messageId);
        if (message == null) {
            throw new RuntimeException("消息不存在");
        }

        // 1.1 归属校验: 消息所属会话必须属于当前登录用户（禁止对他人消息刷反馈）
        Conversation conversation = conversationMapper.selectById(message.getConversationId());
        if (conversation == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAMS, "会话不存在");
        }
        SecurityUtils.checkOwnership(conversation.getUserId());

        // 2. 更新反馈字段
        message.setFeedbackType(feedbackType);
        message.setFeedbackTime(LocalDateTime.now());
        messageMapper.updateById(message);

        // 3. 清除该会话的缓存，确保下次获取时从数据库读取最新数据
        String cacheKey = CacheConfig.CacheConstants.KEY_CONVERSATION_CONTEXT + message.getConversationId();
        cacheService.delete(CacheConfig.CacheConstants.CACHE_CONVERSATION_CONTEXT, cacheKey);
        log.debug("Cleared conversation context cache for conversationId: {}", message.getConversationId());

        // 4. 如果是AI消息，同步更新QA日志的反馈
        if ("assistant".equals(message.getRole())) {
            QaLog qaLog = qaLogMapper.selectOne(new LambdaQueryWrapper<QaLog>()
                    .eq(QaLog::getAnswer, message.getContent())
                    .orderByDesc(QaLog::getCreateTime)
                    .last("LIMIT 1"));
            if (qaLog != null) {
                qaLog.setFeedbackType(feedbackType);
                qaLog.setFeedbackTime(LocalDateTime.now());
                qaLogMapper.updateById(qaLog);
            }
        }

        return message;
    }
}