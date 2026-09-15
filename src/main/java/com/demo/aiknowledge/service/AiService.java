package com.demo.aiknowledge.service;

import com.demo.aiknowledge.dto.AiResponse;

public interface AiService {
    void parseDocument(String filePath, Long docId);

    /**
     * 根据上下文回答问题
     * @param question 用户问题
     * @param context 相关文档上下文
     * @param userId 用户ID
     * @return AI回答对象
     */
    AiResponse ask(String question, String context, Long userId, Long conversationId);

    /**
     * 流式问答(SSE 转发): 逐事件回调 Python 服务 /api/ask/stream 返回的事件
     *
     * <p>Python 侧事件协议（每个事件一个 JSON）:
     * <ul>
     *   <li>{"type":"routed","task_type":"..."}  路由结果</li>
     *   <li>{"type":"token","content":"增量文本"} 逐 token</li>
     *   <li>{"type":"end","content":"完整回答"}   生成结束（注意: sources 事件在其后）</li>
     *   <li>{"type":"sources","sources":[...]"}  引用来源</li>
     *   <li>{"type":"error","content":"..."}      错误</li>
     * </ul>
     *
     * @param onEvent 每收到一个事件即回调（在调用线程上同步执行, 不要做重活）
     */
    void askStream(String question, String context, Long userId, Long conversationId,
                   java.util.function.Consumer<java.util.Map<String, Object>> onEvent);

    /**
     * 管理端流式问答（SSE 转发）：与管理端同步链路 askForAdmin 行为一致，
     * 区别只是逐事件回调（事件契约与用户端 askStream 完全相同）。
     *
     * <p>刻意不透传 conversationId：管理端会话表(admin_conversation)与用户会话表
     * (conversation)主键各自独立，若透传会共用 Python 侧 Redis 记忆键 conv:ctx:&lt;id&gt;，
     * 造成两侧对话记忆串号。管理端上下文仍由调用方拼好随 context 传入。
     *
     * @param adminId 管理员ID（取自 JWT）
     * @param onEvent 每收到一个事件即回调（在调用线程上同步执行, 不要做重活）
     */
    void askStreamForAdmin(String question, String context, Long adminId,
                           java.util.function.Consumer<java.util.Map<String, Object>> onEvent);

    /**
     * 生成会话标题并更新数据库
     * @param conversationId 会话ID
     * @param question 用户问题
     */
    void generateTitle(Long conversationId, String question);

    /**
     * 删除文档向量索引
     */
    void deleteDoc(Long docId);

    /**
     * 管理端AI助手问答
     * @param question 用户问题
     * @param context 对话上下文
     * @param adminId 管理员ID
     * @return AI回答对象
     */
    java.util.Map<String, Object> askForAdmin(String question, String context, Long adminId);
}
