package com.demo.aiknowledge.service;

import com.demo.aiknowledge.entity.AdminConversation;
import com.demo.aiknowledge.entity.AdminMessage;
import java.util.List;

public interface AdminChatService {
    AdminConversation createConversation(Long adminId, String title);
    List<AdminConversation> getHistory(Long adminId);
    AdminMessage sendMessage(Long adminId, Long conversationId, String content);

    /**
     * 管理端流式回答（SSE）—— 与用户端 {@code ChatStreamService#streamAnswer} 同构:
     * tx1 管理员消息先落库 → 流式转发 Python 事件 → tx2 落 AI 回答 → 发送 saved 事件。
     *
     * <p>线程模型: 请求线程只做校验 + tx1 + 返回 emitter, 转发在独立线程执行
     * （SSE 是长连接, 同步等待会占满 Tomcat 线程）。
     *
     * @param adminId        当前登录管理员（来自 JWT, 不信任前端传参）
     * @param conversationId 管理端会话ID（会做归属校验）
     * @param content        管理员问题
     */
    org.springframework.web.servlet.mvc.method.annotation.SseEmitter streamAnswer(
            Long adminId, Long conversationId, String content);
    List<AdminMessage> getMessages(Long conversationId);
    void deleteConversation(Long conversationId);
    AdminConversation updateConversation(Long conversationId, String title, Boolean isPinned);
    AdminMessage submitFeedback(Long messageId, String feedbackType);
}