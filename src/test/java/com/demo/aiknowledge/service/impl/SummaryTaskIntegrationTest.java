package com.demo.aiknowledge.service.impl;

import com.demo.aiknowledge.entity.ConversationContext;
import com.demo.aiknowledge.entity.Message;
import com.demo.aiknowledge.mapper.ConversationContextMapper;
import com.demo.aiknowledge.service.ConversationContextService;
import com.demo.aiknowledge.service.SummaryGenerator;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 摘要异步链路的集成验证：真连 MySQL + Redis。
 *
 * <p>只验证第 2 步引入的行为：增量生成、游标推进、旧摘要合并、缓存失效。
 * 需要本地 MySQL/Redis 可用（与主服务一致的环境）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class SummaryTaskIntegrationTest {

    private static final long CID = 999_888_001L;
    private static final String CACHE_KEY = "conv:summary:" + CID;

    @Autowired
    private ConversationContextService service;
    @Autowired
    private ConversationContextMapper contextMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    /** 不真的去调 Python：LLM 路径由单测覆盖，这里只关心"拿到摘要后怎么落库" */
    @MockBean
    private SummaryGenerator summaryGenerator;

    @BeforeEach
    void cleanUp() {
        // 默认 LLM 可用；降级场景在具体用例里覆盖这个 stub
        when(summaryGenerator.summarize(anyLong(), any(), anyList()))
                .thenReturn(Optional.of("用户希望用 Docker 部署项目，监控方案尚未确定。"));
        jdbcTemplate.update("DELETE FROM message WHERE conversation_id = ?", CID);
        jdbcTemplate.update("DELETE FROM conversation_context WHERE conversation_id = ?", CID);
        stringRedisTemplate.delete(CACHE_KEY);
        // updateConversationContext 会写会话上下文缓存（conv:ctx:），一并清掉
        stringRedisTemplate.delete("conv:ctx:" + CID);
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    private void insertMessages(int count, String contentPrefix) {
        for (int i = 0; i < count; i++) {
            jdbcTemplate.update(
                    "INSERT INTO message (conversation_id, role, content, create_time) VALUES (?, ?, ?, NOW())",
                    CID, i % 2 == 0 ? "user" : "assistant",
                    contentPrefix + " 第" + i + "条 项目 部署 方案 测试");
        }
    }

    private long lastMessageId() {
        Long id = jdbcTemplate.queryForObject(
                "SELECT MAX(id) FROM message WHERE conversation_id = ?", Long.class, CID);
        return id == null ? 0L : id;
    }

    private ConversationContext awaitReady(long expectedVersion) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        ConversationContext ctx = null;
        while (System.currentTimeMillis() < deadline) {
            ctx = contextMapper.selectOne(new LambdaQueryWrapper<ConversationContext>()
                    .eq(ConversationContext::getConversationId, CID));
            if (ctx != null && "ready".equals(ctx.getSummaryStatus())
                    && ctx.getSummaryVersion() != null && ctx.getSummaryVersion() == expectedVersion) {
                return ctx;
            }
            if (ctx != null && "failed".equals(ctx.getSummaryStatus())) {
                fail("摘要任务失败，status=failed");
            }
            Thread.sleep(100);
        }
        return ctx;
    }

    /** 只看版本号，不关心最终状态（降级场景 status 是 failed） */
    private ConversationContext awaitVersion(int expectedVersion) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        ConversationContext ctx = null;
        while (System.currentTimeMillis() < deadline) {
            ctx = contextMapper.selectOne(new LambdaQueryWrapper<ConversationContext>()
                    .eq(ConversationContext::getConversationId, CID));
            if (ctx != null && ctx.getSummaryVersion() != null && ctx.getSummaryVersion() == expectedVersion) {
                return ctx;
            }
            Thread.sleep(100);
        }
        return ctx;
    }

    @Test
    @DisplayName("首次触发：生成摘要、推进游标、标记 ready、失效缓存")
    void generatesSummaryAndAdvancesCursor() throws InterruptedException {
        insertMessages(6, "第一批");
        long expectedCursor = lastMessageId();

        service.triggerSummaryIfNeeded(CID);
        ConversationContext ctx = awaitReady(1);

        assertNotNull(ctx, "异步任务应在 10s 内完成");
        assertEquals("ready", ctx.getSummaryStatus());
        assertEquals(1, ctx.getSummaryVersion());
        assertEquals(expectedCursor, ctx.getSummaryMsgId(), "游标应指向已覆盖的最后一条消息");
        assertNotNull(ctx.getSummary());
        assertTrue(ctx.getSummary().contains("Docker"), "应写入 LLM 返回的摘要，实际: " + ctx.getSummary());
        assertFalse(Boolean.TRUE.equals(stringRedisTemplate.hasKey(CACHE_KEY)),
                "生成后应失效摘要缓存，避免读到旧内容");
    }

    @Test
    @DisplayName("增量触发：第二次只处理新消息，并把上一版摘要传给生成器")
    void secondRunMergesPreviousSummary() throws InterruptedException {
        insertMessages(6, "第一批");
        service.triggerSummaryIfNeeded(CID);
        ConversationContext first = awaitReady(1);
        assertNotNull(first);
        String firstSummary = first.getSummary();

        insertMessages(5, "第二批");
        long expectedCursor = lastMessageId();

        service.triggerSummaryIfNeeded(CID);
        ConversationContext second = awaitReady(2);

        assertNotNull(second);
        assertEquals(2, second.getSummaryVersion());
        assertEquals(expectedCursor, second.getSummaryMsgId());

        // 合并发生在 LLM 侧：这里验证"上一版摘要确实被传进去了"，否则增量会丢历史
        ArgumentCaptor<String> oldSummary = ArgumentCaptor.forClass(String.class);
        verify(summaryGenerator, times(2)).summarize(eq(CID), oldSummary.capture(), anyList());
        assertEquals(firstSummary, oldSummary.getAllValues().get(1),
                "第二次生成必须带上上一版摘要");
    }

    @Test
    @DisplayName("LLM 不可用时降级为关键词桩，且状态可区分")
    void degradesToKeywordStubWhenLlmUnavailable() throws InterruptedException {
        when(summaryGenerator.summarize(anyLong(), any(), anyList())).thenReturn(Optional.empty());
        insertMessages(6, "降级");

        service.triggerSummaryIfNeeded(CID);
        ConversationContext ctx = awaitVersion(1);

        assertNotNull(ctx, "降级也必须完成，不能卡在 generating");
        assertEquals("failed", ctx.getSummaryStatus(), "降级产物要与 LLM 生成区分开");
        assertTrue(ctx.getSummary().contains("关键话题"),
                "降级内容应来自关键词桩，实际: " + ctx.getSummary());
        assertNotNull(ctx.getSummaryMsgId(), "降级也要推进游标，否则会反复重试同一批消息");
        assertTrue(ctx.getSummaryMsgId() > 0);
    }

    @Test
    @DisplayName("经 updateConversationContext 在事务提交后才触发")
    void triggersAfterCommit() throws InterruptedException {
        insertMessages(6, "事务路径");
        long lastId = lastMessageId();

        Message msg = new Message();
        msg.setId(lastId);
        msg.setConversationId(CID);
        msg.setRole("assistant");
        msg.setContent("最后一条消息");
        msg.setCreateTime(LocalDateTime.now());

        // 该方法带 @Transactional：若摘要触发写在事务内，异步线程会读不到刚插入的消息
        service.updateConversationContext(CID, 1L, msg);

        ConversationContext ctx = awaitReady(1);
        assertNotNull(ctx, "事务提交后应触发摘要");
        assertEquals("ready", ctx.getSummaryStatus());
    }

    @Test
    @DisplayName("删掉 Redis 摘要缓存后仍能读到（回源 MySQL 并回填）")
    void readsFromDbAfterCacheRemoved() throws InterruptedException {
        insertMessages(6, "缓存路径");
        service.triggerSummaryIfNeeded(CID);
        ConversationContext ctx = awaitReady(1);
        String expected = ctx.getSummary();

        // 第一次读取会回填缓存
        assertEquals(expected, service.getConversationSummaryIfPresent(CID));
        assertTrue(Boolean.TRUE.equals(stringRedisTemplate.hasKey(CACHE_KEY)), "首次读取应回填缓存");

        // 模拟缓存丢失：Redis 只是缓存，删掉必须仍然正确
        stringRedisTemplate.delete(CACHE_KEY);
        assertEquals(expected, service.getConversationSummaryIfPresent(CID));
        assertTrue(Boolean.TRUE.equals(stringRedisTemplate.hasKey(CACHE_KEY)), "回源后应重新回填");
    }

    @Test
    @DisplayName("消息不足阈值时不触发，不会凭空写出摘要")
    void doesNotTriggerBelowThreshold() throws InterruptedException {
        insertMessages(3, "少量");

        service.triggerSummaryIfNeeded(CID);
        Thread.sleep(500);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT summary FROM conversation_context WHERE conversation_id = ?", CID);
        assertTrue(rows.isEmpty() || rows.get(0).get("summary") == null,
                "未达阈值不应写入摘要");
    }
}
