package com.demo.aiknowledge.service.impl;

import com.demo.aiknowledge.entity.Message;
import com.demo.aiknowledge.entity.QaLog;
import com.demo.aiknowledge.mapper.MessageMapper;
import com.demo.aiknowledge.mapper.QaLogMapper;
import com.demo.aiknowledge.service.ChatPersistenceService;
import com.demo.aiknowledge.service.ConversationContextService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 聊天消息持久化服务实现 —— 每个方法一个独立短事务
 *
 * 事务边界说明见 {@link ChatPersistenceService} 接口文档。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ChatPersistenceServiceImpl implements ChatPersistenceService {

    private final MessageMapper messageMapper;
    private final QaLogMapper qaLogMapper;
    private final ConversationContextService conversationContextService;

    @Override
    @Transactional
    public Message saveUserMessage(Long conversationId, Long userId, String content) {
        Message userMsg = new Message();
        userMsg.setConversationId(conversationId);
        userMsg.setRole("user");
        userMsg.setContent(content);
        userMsg.setCreateTime(LocalDateTime.now());
        messageMapper.insert(userMsg);

        // 更新会话上下文（同事务内读缓存/回源可见刚插入的消息, 按 id 去重防重复追加）
        conversationContextService.updateConversationContext(conversationId, userId, userMsg);
        log.debug("User message persisted - conversationId: {}, messageId: {}", conversationId, userMsg.getId());
        return userMsg;
    }

    @Override
    @Transactional
    public Message saveAssistantMessage(Long conversationId, Long userId, String question,
                                        String answer, String sourcesJson, String taskType) {
        Message aiMsg = new Message();
        aiMsg.setConversationId(conversationId);
        aiMsg.setRole("assistant");
        aiMsg.setContent(answer);
        aiMsg.setSources(sourcesJson);
        aiMsg.setTaskType(taskType);
        aiMsg.setCreateTime(LocalDateTime.now());
        messageMapper.insert(aiMsg);

        conversationContextService.updateConversationContext(conversationId, userId, aiMsg);

        QaLog qaLog = new QaLog();
        qaLog.setUserId(userId);
        qaLog.setQuestion(question);
        qaLog.setAnswer(answer);
        qaLog.setCreateTime(LocalDateTime.now());
        qaLogMapper.insert(qaLog);

        log.debug("Assistant message persisted - conversationId: {}, messageId: {}", conversationId, aiMsg.getId());
        return aiMsg;
    }
}
