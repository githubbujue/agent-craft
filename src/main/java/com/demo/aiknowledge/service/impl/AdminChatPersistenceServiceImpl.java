package com.demo.aiknowledge.service.impl;

import com.demo.aiknowledge.entity.AdminConversation;
import com.demo.aiknowledge.entity.AdminMessage;
import com.demo.aiknowledge.mapper.AdminConversationMapper;
import com.demo.aiknowledge.mapper.AdminMessageMapper;
import com.demo.aiknowledge.service.AdminChatPersistenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 管理端聊天持久化服务实现 —— 每个方法一个独立短事务
 *
 * 事务边界说明见 {@link AdminChatPersistenceService} 接口文档。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AdminChatPersistenceServiceImpl implements AdminChatPersistenceService {

    private final AdminMessageMapper adminMessageMapper;
    private final AdminConversationMapper adminConversationMapper;

    @Override
    @Transactional
    public AdminMessage saveUserMessage(Long conversationId, String content) {
        AdminMessage userMsg = new AdminMessage();
        userMsg.setConversationId(conversationId);
        userMsg.setRole("user");
        userMsg.setContent(content);
        userMsg.setCreateTime(LocalDateTime.now());
        adminMessageMapper.insert(userMsg);
        log.debug("Admin message persisted - conversationId: {}, messageId: {}",
                conversationId, userMsg.getId());
        return userMsg;
    }

    @Override
    @Transactional
    public AdminMessage saveAssistantMessage(AdminConversation conversation, String question,
                                             String answer, String sourcesJson, String taskType) {
        AdminMessage aiMsg = new AdminMessage();
        aiMsg.setConversationId(conversation.getId());
        aiMsg.setRole("assistant");
        aiMsg.setContent(answer);
        aiMsg.setSources(sourcesJson);
        aiMsg.setTaskType(taskType);
        aiMsg.setCreateTime(LocalDateTime.now());
        adminMessageMapper.insert(aiMsg);

        // 标题仍为默认值时, 用首条提问更新（同事务内完成）
        String title = conversation.getTitle();
        if (title == null || title.isEmpty() || title.startsWith("新对话") || title.startsWith("新建会话")) {
            String newTitle = question.length() > 30 ? question.substring(0, 30) + "..." : question;
            conversation.setTitle(newTitle);
            adminConversationMapper.updateById(conversation);
        }

        log.debug("Admin assistant message persisted - conversationId: {}, messageId: {}",
                conversation.getId(), aiMsg.getId());
        return aiMsg;
    }
}
