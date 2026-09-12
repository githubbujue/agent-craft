package com.demo.aiknowledge.service;

import com.demo.aiknowledge.entity.AdminConversation;
import com.demo.aiknowledge.entity.AdminMessage;

/**
 * 管理端聊天持久化服务 —— 独立 Bean, 专用于划分短事务边界
 *
 * <p>与管理端 sendMessage 原实现的问题一致: 整体 @Transactional 包裹
 * HTTP 调用(callAdminAgent → Python 管理助手, 数秒), 长事务占用数据库连接、
 * 失败时用户消息被一并回滚。事务边界设计与 {@link ChatPersistenceService} 相同:
 *
 * <pre>
 *   tx1: 保存管理员消息                      → 立即提交
 *   ─── 事务外: 调用 AI 管理助手（HTTP）───
 *   tx2: 保存 AI 回答 + 按需更新会话标题
 * </pre>
 */
public interface AdminChatPersistenceService {

    /** 短事务①: 保存管理员发送的消息（立即提交） */
    AdminMessage saveUserMessage(Long conversationId, String content);

    /**
     * 短事务②: 保存 AI 回答, 并在标题为默认值时用首条提问更新标题
     *
     * @param conversation 会话实体（用于标题更新）
     */
    AdminMessage saveAssistantMessage(AdminConversation conversation, String question,
                                      String answer, String sourcesJson, String taskType);
}
