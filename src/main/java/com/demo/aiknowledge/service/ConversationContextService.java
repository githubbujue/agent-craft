package com.demo.aiknowledge.service;

import com.demo.aiknowledge.entity.ConversationContext;
import com.demo.aiknowledge.entity.Message;

import java.util.List;

/**
 * 对话上下文服务
 * 管理对话的短期记忆和长期记忆
 */
public interface ConversationContextService {

    /**
     * 获取对话上下文
     *
     * @param conversationId 对话ID
     * @param maxMessages    最大消息数
     * @return 上下文消息列表
     */
    List<Message> getConversationContext(Long conversationId, int maxMessages);

    /**
     * 更新对话上下文
     *
     * @param conversationId 对话ID
     * @param userId        用户ID
     * @param newMessage    新消息
     */
    void updateConversationContext(Long conversationId, Long userId, Message newMessage);

    /**
     * 生成对话摘要
     *
     * @param conversationId 对话ID
     * @return 对话摘要
     */
    String generateConversationSummary(Long conversationId);

    /**
     * 获取对话摘要
     *
     * @param conversationId 对话ID
     * @return 对话摘要
     */
    String getConversationSummary(Long conversationId);

    /**
     * 获取已存在的对话摘要（只读，不触发现场生成）
     *
     * <p>与 {@link #getConversationSummary(Long)} 的区别：后者在摘要缺失时会重新生成并写库，
     * 适合"确实需要摘要"的场景；本方法只查一次，缺失返回 null，适合在请求链路上做可选补充，
     * 避免每次提问都触发全量消息扫描与写库。
     *
     * @param conversationId 对话ID
     * @return 已存在的摘要；不存在返回 null
     */
    String getConversationSummaryIfPresent(Long conversationId);

    /**
     * 按需触发异步摘要生成（增量、可并发抑制、失败不影响主流程）
     *
     * <p>触发条件：未覆盖的消息条数达到阈值。抢占方式为 CAS 更新
     * {@code summary_status}，同一会话同时只允许一个任务在跑。
     *
     * @param conversationId 对话ID
     */
    void triggerSummaryIfNeeded(Long conversationId);

    /**
     * 保存长期记忆
     *
     * @param conversationId 对话ID
     * @param userId        用户ID
     */
    void saveLongTermMemory(Long conversationId, Long userId);

    /**
     * 检索相似对话（跨会话）
     *
     * @param userId                用户ID
     * @param excludeConversationId 要排除的会话（通常是当前会话，避免把自己的摘要当历史）
     * @param query                 查询内容
     * @param topK                  返回数量
     * @return 相似对话列表，按相关度降序；无命中返回空列表
     */
    List<ConversationContext> findSimilarConversations(Long userId, Long excludeConversationId,
                                                       String query, int topK);

    /**
     * 构建传给 AI 服务的对话上下文。
     *
     * <p>内容 = 当前会话更早对话的摘要 + 可能相关的历史会话。
     * 最近几轮原文不在这里传 —— 由 Python 侧从 Redis 读取，两边都传会导致重复注入。
     *
     * <p>两条链路（{@code ChatServiceImpl} / {@code ChatStreamServiceImpl}）共用，
     * 此前各写了一份"取摘要加前缀"的逻辑，改一处忘一处。
     *
     * @param conversationId 当前会话ID
     * @param userId         用户ID
     * @param question       用户本次提问（用于跨会话召回）
     * @return 可直接作为 prompt 上下文使用的字符串；无内容时返回空串
     */
    String buildPromptContext(Long conversationId, Long userId, String question);

    /**
     * 清理过期上下文
     *
     * @param days 保留天数
     */
    void cleanupExpiredContexts(int days);

    /**
     * 获取上下文窗口大小
     *
     * @param conversationId 对话ID
     * @return 窗口大小
     */
    int getContextWindowSize(Long conversationId);

    /**
     * 调整上下文窗口大小
     *
     * @param conversationId 对话ID
     * @param windowSize    新的窗口大小
     */
    void adjustContextWindowSize(Long conversationId, int windowSize);

    /**
     * 计算消息重要性
     *
     * @param message 消息
     * @return 重要性评分
     */
    double calculateMessageImportance(Message message);

    /**
     * 压缩对话历史
     *
     * @param conversationId 对话ID
     * @return 压缩后的消息数量
     */
    int compressConversationHistory(Long conversationId);
}