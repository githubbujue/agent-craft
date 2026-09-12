package com.demo.aiknowledge.service;

import com.demo.aiknowledge.entity.Message;

/**
 * 聊天消息持久化服务 —— 独立 Bean, 专用于划分短事务边界
 *
 * <p>设计动机（原实现的问题）:
 * sendMessage 整体标注 @Transactional, 事务内同步调用 AI 服务（HTTP + LLM, 5-15 秒）,
 * 导致三类问题:
 * <ol>
 *   <li>长事务占用数据库连接 —— 高并发下连接池耗尽, 其他请求全部阻塞</li>
 *   <li>AI 调用失败时用户消息被一并回滚, 而 Python 侧会话记忆已写入
 *       —— HTTP 无法回滚, 跨系统状态不一致</li>
 *   <li>Redis 写入不参与数据库事务, 回滚后遗留脏缓存</li>
 * </ol>
 *
 * <p>拆分后的事务边界:
 * <pre>
 *   tx1: 保存用户消息 + 更新上下文   → 立即提交（AI 失败也不丢用户输入）
 *   ─── 事务外: 调用 AI 服务（HTTP/LLM）───
 *   tx2: 保存 AI 回答 + 更新上下文 + QA 日志
 * </pre>
 *
 * <p>为什么必须独立成 Bean:
 * Spring @Transactional 基于 AOP 代理, 同类内部自调用不经过代理, 注解会失效 ——
 * 把 tx1/tx2 写成 private 方法自调用是拆分事务最典型的坑。
 */
public interface ChatPersistenceService {

    /**
     * 短事务①: 保存用户消息并更新会话上下文（立即提交）
     *
     * @return 已持久化的用户消息（含自增 id）
     */
    Message saveUserMessage(Long conversationId, Long userId, String content);

    /**
     * 短事务②: 保存 AI 回答、更新会话上下文、记录 QA 日志
     *
     * @return 已持久化的 AI 消息（含自增 id）
     */
    Message saveAssistantMessage(Long conversationId, Long userId, String question,
                                 String answer, String sourcesJson, String taskType);
}
