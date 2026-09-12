package com.demo.aiknowledge.service;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 聊天流式回答服务（SSE）
 *
 * <p>链路: 浏览器 fetch(ReadableStream) ←SSE— Java SseEmitter ←逐行读取— Python /api/ask/stream
 *
 * <p>与同步链路（ChatService#sendMessage）的关键差异:
 * <ol>
 *   <li>回答逐 token 推送给前端, 首字延迟从"整段生成时间"降到"首个 token 时间"</li>
 *   <li>生成期间不持有数据库事务（沿用事务重构的边界原则）:
 *       tx1 用户消息先提交 → 流式生成（事务外）→ tx2 落完整回答</li>
 *   <li>客户端中途断开（用户点"停止"）时, 已生成的非空内容仍会保存, 避免对话出现空白</li>
 * </ol>
 */
public interface ChatStreamService {

    /**
     * 发起一次流式问答。
     *
     * <p>调用本方法时会同步完成三件事: 会话归属校验、用户消息落库(tx1)、首条消息触发标题生成;
     * 随后返回 SseEmitter, 由独立线程持续转发 Python 侧事件。
     *
     * @param userId         当前登录用户（来自 JWT, 不信任前端传参）
     * @param conversationId 会话ID（会做归属校验）
     * @param content        用户问题
     * @return SSE 发射器（前端按 text/event-stream 读取）
     */
    SseEmitter streamAnswer(Long userId, Long conversationId, String content);
}
