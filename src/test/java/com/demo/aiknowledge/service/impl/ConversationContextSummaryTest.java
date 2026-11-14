package com.demo.aiknowledge.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.demo.aiknowledge.entity.ConversationContext;
import com.demo.aiknowledge.entity.Message;
import com.demo.aiknowledge.mapper.ConversationContextMapper;
import com.demo.aiknowledge.mapper.MessageMapper;
import com.demo.aiknowledge.service.CacheService;
import com.demo.aiknowledge.service.SummaryGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskExecutor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * 摘要异步触发的并发抑制逻辑。
 *
 * <p>这类验证不适合只靠端到端：真实任务很快跑完，游标一推进，后续调用本来就不会再触发，
 * 掩盖了"同一条消息被两个线程同时触发"的竞态。这里直接控制 CAS 返回值来验证判定分支。
 */
@ExtendWith(MockitoExtension.class)
class ConversationContextSummaryTest {

    @Mock
    private MessageMapper messageMapper;
    @Mock
    private ConversationContextMapper conversationContextMapper;
    @Mock
    private CacheService cacheService;
    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private TaskExecutor taskExecutor;
    @Mock
    private ObjectMapper objectMapper;
    @Mock
    private SummaryGenerator summaryGenerator;

    private ConversationContextServiceImpl service;

    /**
     * 手动构造而不是 @InjectMocks：taskExecutor 是 {@code @Resource} 字段注入（非构造参数），
     * @InjectMocks 对这种"构造器 + 字段混合注入"不可靠，会出现 mock 没被注入、
     * 表现为"零交互"的假象。
     */
    @BeforeEach
    void setUp() {
        service = new ConversationContextServiceImpl(
                messageMapper, conversationContextMapper, cacheService,
                objectMapper, stringRedisTemplate, summaryGenerator);
        ReflectionTestUtils.setField(service, "taskExecutor", taskExecutor);
    }

    /**
     * LambdaQueryWrapper 解析列名依赖 MyBatis-Plus 的实体元数据缓存，
     * 纯单元测试没有 Spring 上下文，必须手工注册一次，否则调用时抛
     * "can not find lambda cache for this entity"（异常还会被 catch 吞掉，表现为"什么都没执行"）
     */
    @BeforeAll
    static void initTableInfo() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        TableInfoHelper.initTableInfo(assistant, Message.class);
        TableInfoHelper.initTableInfo(assistant, ConversationContext.class);
    }

    private ConversationContext contextWithCursor(Long cursorMsgId) {
        ConversationContext context = new ConversationContext();
        context.setId(1L);
        context.setConversationId(100L);
        context.setSummaryMsgId(cursorMsgId);
        context.setSummaryVersion(0);
        context.setSummaryStatus("none");
        return context;
    }

    @Test
    @DisplayName("未覆盖消息不足 5 条时不触发，也不做 CAS")
    void doesNothingWhenPendingBelowThreshold() {
        when(conversationContextMapper.selectOne(any())).thenReturn(contextWithCursor(0L));
        when(messageMapper.selectCount(any())).thenReturn(3L);

        service.triggerSummaryIfNeeded(100L);

        verify(taskExecutor, never()).execute(any());
        verify(conversationContextMapper, never()).update(any(), any(Wrapper.class));
    }

    @Test
    @DisplayName("CAS 抢占成功才提交任务")
    void submitsTaskWhenClaimSucceeds() {
        when(conversationContextMapper.selectOne(any())).thenReturn(contextWithCursor(0L));
        when(messageMapper.selectCount(any())).thenReturn(6L);
        when(conversationContextMapper.update(
                ArgumentMatchers.<ConversationContext>isNull(), any(Wrapper.class))).thenReturn(1);

        service.triggerSummaryIfNeeded(100L);

        verify(taskExecutor, times(1)).execute(any());
    }

    @Test
    @DisplayName("CAS 抢占失败（已有任务在生成）时不重复提交")
    void skipsWhenAnotherTaskOwnsTheLock() {
        when(conversationContextMapper.selectOne(any())).thenReturn(contextWithCursor(0L));
        when(messageMapper.selectCount(any())).thenReturn(6L);
        when(conversationContextMapper.update(
                ArgumentMatchers.<ConversationContext>isNull(), any(Wrapper.class))).thenReturn(0);

        service.triggerSummaryIfNeeded(100L);

        verify(taskExecutor, never()).execute(any());
    }

    @Test
    @DisplayName("线程池拒绝执行时把状态回滚，避免永久卡在 generating")
    void rollsBackStatusWhenExecutorRejects() {
        when(conversationContextMapper.selectOne(any())).thenReturn(contextWithCursor(0L));
        when(messageMapper.selectCount(any())).thenReturn(6L);
        when(conversationContextMapper.update(
                ArgumentMatchers.<ConversationContext>isNull(), any(Wrapper.class))).thenReturn(1);
        doThrow(new RuntimeException("pool is full")).when(taskExecutor).execute(any());

        service.triggerSummaryIfNeeded(100L);

        // 抢占一次 + 回滚一次
        verify(conversationContextMapper, times(2))
                .update(ArgumentMatchers.<ConversationContext>isNull(), any(Wrapper.class));
    }

    @Test
    @DisplayName("上下文行不存在时懒创建，否则游标无处存放")
    void createsContextRowWhenMissing() {
        when(conversationContextMapper.selectOne(any())).thenReturn(null);
        when(conversationContextMapper.insert(any(ConversationContext.class))).thenReturn(1);
        when(messageMapper.selectCount(any())).thenReturn(6L);
        when(conversationContextMapper.update(
                ArgumentMatchers.<ConversationContext>isNull(), any(Wrapper.class))).thenReturn(1);

        service.triggerSummaryIfNeeded(100L);

        verify(conversationContextMapper).insert(any(ConversationContext.class));
        verify(taskExecutor, times(1)).execute(any());
    }
}
