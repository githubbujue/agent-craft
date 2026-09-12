package com.demo.aiknowledge.controller;

import com.demo.aiknowledge.common.Result;
import com.demo.aiknowledge.common.SecurityUtils;
import com.demo.aiknowledge.dto.ChatRequest;
import com.demo.aiknowledge.service.ChatStreamService;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import com.demo.aiknowledge.dto.FeedbackRequest;
import com.demo.aiknowledge.entity.Conversation;
import com.demo.aiknowledge.entity.Message;
import com.demo.aiknowledge.service.ChatService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;
    private final ChatStreamService chatStreamService;

    /**
     * 流式问答（SSE）
     *
     * <p>事件契约（与 Python /api/ask/stream 一致, 末尾追加 saved）:
     * routed / token / end / sources / error, 最后 saved 事件携带落库后的 messageId。
     *
     * <p>为什么用 POST 而非 EventSource: EventSource 只支持 GET 且无法携带
     * Authorization 头 —— 前端改用 fetch + ReadableStream 读取本端点。
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamMessage(@RequestBody ChatRequest request) {
        // 身份取自 JWT（与同步链路一致, 不信任前端传参）
        Long userId = SecurityUtils.getCurrentUserId();
        return chatStreamService.streamAnswer(userId, request.getConversationId(), request.getContent());
    }

    @PostMapping("/conversations")
    public Result<Conversation> createConversation(@RequestParam(required = false) String title) {
        // 身份来自 JWT（SecurityUtils 读取过滤器写入的认证主体）, 不再接收前端传入的 userId
        Long userId = SecurityUtils.getCurrentUserId();
        return Result.success(chatService.createConversation(userId, title));
    }

    @GetMapping("/conversations")
    public Result<List<Conversation>> getHistory() {
        // 会话列表恒为"当前登录用户"的, 杜绝 ?userId=别人 的水平越权
        Long userId = SecurityUtils.getCurrentUserId();
        return Result.success(chatService.getHistory(userId));
    }

    @PostMapping("/messages")
    public Result<Message> sendMessage(@RequestBody ChatRequest request) {
        // 身份取自 token; 请求体中的 userId 一律忽略（可被伪造）
        Long userId = SecurityUtils.getCurrentUserId();
        return Result.success(chatService.sendMessage(userId, request.getConversationId(), request.getContent()));
    }

    @GetMapping("/messages")
    public Result<List<Message>> getMessages(@RequestParam Long conversationId) {
        return Result.success(chatService.getMessages(conversationId));
    }

    @DeleteMapping("/conversations/{id}")
    public Result<String> deleteConversation(@PathVariable Long id) {
        chatService.deleteConversation(id);
        return Result.success("Conversation deleted");
    }

    @PutMapping("/conversations/{id}")
    public Result<Conversation> updateConversation(@PathVariable Long id, @RequestBody Conversation conversation) {
        return Result.success(chatService.updateConversation(id, conversation.getTitle(), conversation.getIsPinned()));
    }

    @GetMapping("/test-auth")
    public Result<String> testAuth() {
        return Result.success("Authentication successful - you have USER role access");
    }

    // 临时图片上传API，用于用户端上传图片，不会添加到知识库
    @PostMapping("/upload/image")
    public Result<Map<String, Object>> uploadImage(@RequestParam("file") MultipartFile file) {
        String fileName = file.getOriginalFilename();
        if (fileName == null) fileName = "unknown";
        String uuid = UUID.randomUUID().toString();
        String savedFileName = uuid + "_" + fileName;
        String filePath;

        // 保存到本地临时目录
        try {
            String uploadDir = "D:/aiknowledge/temp";
            File dir = new File(uploadDir);
            if (!dir.exists()) {
                dir.mkdirs();
            }
            File savedFile = new File(dir, savedFileName);
            file.transferTo(savedFile);
            filePath = savedFile.getAbsolutePath();

            // 返回文件信息
            Map<String, Object> result = new HashMap<>();
            result.put("id", uuid);
            result.put("name", fileName);
            result.put("path", filePath);
            result.put("url", "/api/chat/view/image/" + uuid);

            return Result.success(result);
        } catch (IOException e) {
            throw new RuntimeException("图片上传失败");
        }
    }

    // 查看临时图片
    @GetMapping("/view/image/{id}")
    public ResponseEntity<Resource> viewImage(@PathVariable String id) {
        try {
            String uploadDir = "D:/aiknowledge/temp";
            File dir = new File(uploadDir);
            if (!dir.exists()) {
                return ResponseEntity.notFound().build();
            }

            // 查找对应ID的文件
            File[] files = dir.listFiles((d, name) -> name.startsWith(id + "_"));
            if (files == null || files.length == 0) {
                return ResponseEntity.notFound().build();
            }

            File file = files[0];
            Resource resource = new FileSystemResource(file);
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(getContentType(file.getName())))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=" + file.getName())
                    .body(resource);
        } catch (Exception e) {
            return ResponseEntity.status(500).build();
        }
    }

    // 获取文件类型
    private String getContentType(String fileName) {
        String ext = fileName.substring(fileName.lastIndexOf('.')).toLowerCase();
        switch (ext) {
            case ".png": return "image/png";
            case ".jpg": case ".jpeg": return "image/jpeg";
            case ".gif": return "image/gif";
            case ".bmp": return "image/bmp";
            default: return "application/octet-stream";
        }
    }

    // 消息反馈接口
    @PostMapping("/messages/feedback")
    public Result<Message> submitFeedback(@RequestBody FeedbackRequest request) {
        return Result.success(chatService.submitFeedback(request.getMessageId(), request.getFeedbackType()));
    }

    // 清理临时文件（可选）
    @PostMapping("/cleanup/temp")
    public Result<String> cleanupTemp() {
        try {
            String uploadDir = "D:/aiknowledge/temp";
            File dir = new File(uploadDir);
            if (dir.exists()) {
                File[] files = dir.listFiles();
                if (files != null) {
                    for (File file : files) {
                        // 删除24小时前的文件
                        if (System.currentTimeMillis() - file.lastModified() > 24 * 60 * 60 * 1000) {
                            file.delete();
                        }
                    }
                }
            }
            return Result.success("临时文件清理成功");
        } catch (Exception e) {
            return Result.error("临时文件清理失败");
        }
    }
}
