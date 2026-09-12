package com.demo.aiknowledge.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.demo.aiknowledge.dto.AiResponse;
import com.demo.aiknowledge.entity.AdminConversation;
import com.demo.aiknowledge.entity.AdminMessage;
import com.demo.aiknowledge.mapper.AdminConversationMapper;
import com.demo.aiknowledge.mapper.AdminMessageMapper;
import com.demo.aiknowledge.service.AdminChatPersistenceService;
import com.demo.aiknowledge.service.AdminChatService;
import com.demo.aiknowledge.service.AiService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
        if (conversation != null) {
            if (title != null) {
                conversation.setTitle(title);
            }
            if (isPinned != null) {
                conversation.setIsPinned(isPinned);
            }
            adminConversationMapper.updateById(conversation);
        }
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
    public List<AdminMessage> getMessages(Long conversationId) {
        return adminMessageMapper.selectList(new LambdaQueryWrapper<AdminMessage>()
                .eq(AdminMessage::getConversationId, conversationId)
                .orderByAsc(AdminMessage::getCreateTime));
    }

    @Override
    @Transactional
    public void deleteConversation(Long conversationId) {
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
        message.setCreateTime(LocalDateTime.now());
        adminMessageMapper.updateById(message);
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