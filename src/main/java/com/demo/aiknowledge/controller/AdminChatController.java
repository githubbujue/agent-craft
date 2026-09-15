package com.demo.aiknowledge.controller;

import com.demo.aiknowledge.common.Result;
import com.demo.aiknowledge.common.SecurityUtils;
import com.demo.aiknowledge.dto.FeedbackRequest;
import com.demo.aiknowledge.entity.AdminConversation;
import com.demo.aiknowledge.entity.AdminMessage;
import com.demo.aiknowledge.service.AdminChatService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin-chat")
@RequiredArgsConstructor
public class AdminChatController {

    private final AdminChatService adminChatService;

    @PostMapping("/conversations")
    public Result<AdminConversation> createConversation(
            @RequestParam(required = false) String title) {
        // 身份取自 JWT（管理端 token 中的 userId）, 不信任前端传参
        Long adminId = SecurityUtils.getCurrentUserId();
        return Result.success(adminChatService.createConversation(adminId, title));
    }

    @GetMapping("/conversations")
    public Result<List<AdminConversation>> getHistory() {
        // 恒为当前登录管理员的会话列表
        Long adminId = SecurityUtils.getCurrentUserId();
        return Result.success(adminChatService.getHistory(adminId));
    }

    @PostMapping("/messages")
    public Result<AdminMessage> sendMessage(
            @RequestParam Long conversationId,
            @RequestBody Map<String, String> request) {
        Long adminId = SecurityUtils.getCurrentUserId();
        String content = request.get("content");
        return Result.success(adminChatService.sendMessage(adminId, conversationId, content));
    }

    /**
     * 流式问答（SSE）—— 与用户端 /api/chat/stream 事件契约完全一致,
     * 末尾追加 saved 事件（携带落库后的 messageId）。
     *
     * <p>为什么用 POST 而非 EventSource: EventSource 仅支持 GET 且无法携带
     * Authorization 头 —— 前端用 fetch + ReadableStream 读取本端点。
     */
    @PostMapping(value = "/messages/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamMessage(@RequestBody Map<String, Object> request) {
        // 身份取自 JWT（不信任前端传参, 与同步链路一致）
        Long adminId = SecurityUtils.getCurrentUserId();
        Long conversationId = Long.valueOf(String.valueOf(request.get("conversationId")));
        String content = String.valueOf(request.get("content"));
        return adminChatService.streamAnswer(adminId, conversationId, content);
    }

    @GetMapping("/messages")
    public Result<List<AdminMessage>> getMessages(@RequestParam Long conversationId) {
        return Result.success(adminChatService.getMessages(conversationId));
    }

    @DeleteMapping("/conversations/{id}")
    public Result<String> deleteConversation(@PathVariable Long id) {
        adminChatService.deleteConversation(id);
        return Result.success("Conversation deleted");
    }

    @PutMapping("/conversations/{id}")
    public Result<AdminConversation> updateConversation(
            @PathVariable Long id,
            @RequestBody AdminConversation conversation) {
        return Result.success(adminChatService.updateConversation(id, conversation.getTitle(), conversation.getIsPinned()));
    }

    @PostMapping("/messages/feedback")
    public Result<AdminMessage> submitFeedback(@RequestBody FeedbackRequest request) {
        return Result.success(adminChatService.submitFeedback(request.getMessageId(), request.getFeedbackType()));
    }
}