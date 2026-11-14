package com.demo.aiknowledge.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.demo.aiknowledge.config.CacheConfig;
import com.demo.aiknowledge.entity.ConversationContext;
import com.demo.aiknowledge.entity.Message;
import com.demo.aiknowledge.mapper.ConversationContextMapper;
import com.demo.aiknowledge.mapper.MessageMapper;
import com.demo.aiknowledge.service.CacheService;
import com.demo.aiknowledge.service.ConversationContextService;
import com.demo.aiknowledge.service.SummaryGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskExecutor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 *
 * 对话上下文服务实现
 * 实现短期记忆（Redis缓存）和长期记忆（数据库）管理
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ConversationContextServiceImpl implements ConversationContextService {

    private final MessageMapper messageMapper;
    private final ConversationContextMapper conversationContextMapper;
    private final CacheService cacheService;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final SummaryGenerator summaryGenerator;
    /**
     * 摘要专用线程池（summaryTaskExecutor），与 SSE 转发的 applicationTaskExecutor 隔离。
     * 用字段注入是因为 @RequiredArgsConstructor 生成的构造参数带不上 @Qualifier，
     * 而容器里有多个 TaskExecutor，按类型注入会冲突。
     */
    @Resource(name = "summaryTaskExecutor")
    private TaskExecutor taskExecutor;

    // 默认上下文窗口大小
    private static final int DEFAULT_WINDOW_SIZE = 10;
    // 最大上下文窗口大小
    private static final int MAX_WINDOW_SIZE = 20;
    // 最小上下文窗口大小
    private static final int MIN_WINDOW_SIZE = 5;

    // 摘要缓存 key：Java 自成命名空间。
    // 刻意不复用 Python 侧的 conversation:{id}:summary —— 那边写入的是 LLM 压缩摘要，
    // 且 memory_read 命中后直接使用、不再压缩。两边混用会让 Python 把这里的关键词桩
    // 当成 LLM 摘要直接注入 Prompt，属于静默的语义污染。第 3 步收敛生成器时再统一。
    private static final String SUMMARY_CACHE_PREFIX = "conv:summary:";
    private static final long SUMMARY_CACHE_TTL_SECONDS = 3600;

    // 摘要状态机：none(未生成) / generating(生成中) / ready(可用) / failed(上次失败,可被下次覆盖)
    private static final String SUMMARY_STATUS_NONE = "none";
    private static final String SUMMARY_STATUS_GENERATING = "generating";
    private static final String SUMMARY_STATUS_READY = "ready";
    private static final String SUMMARY_STATUS_FAILED = "failed";

    // 未覆盖消息达到该条数才触发一次摘要（与旧的"每 5 条"语义保持一致）
    private static final int SUMMARY_TRIGGER_MIN_NEW_MESSAGES = 5;
    // 单次任务最多处理的消息条数，防止长会话一次拉爆内存与耗时
    private static final int SUMMARY_BATCH_LIMIT = 200;
    // 合并旧摘要时保留的尾部字符数 / 摘要总长上限，防止摘要随轮次无限膨胀
    private static final int SUMMARY_CARRY_CHARS = 800;
    private static final int SUMMARY_MAX_CHARS = 2000;
    // generating 状态超过该分钟数视为任务已死（进程重启/线程池丢失），允许被重新抢占
    private static final long SUMMARY_STALE_MINUTES = 10;

    @Override
    public List<Message> getConversationContext(Long conversationId, int maxMessages) {
        String cacheKey = CacheConfig.CacheConstants.KEY_CONVERSATION_CONTEXT + conversationId;

        // 1. 先查 Redis (获取原始 Object 列表，避免直接强转泛型导致 ClassCastException)
        // 注意：这里泛型指定为 List.class，内部元素会是 LinkedHashMap
        List<Object> cachedRawObjects = cacheService.get(
                CacheConfig.CacheConstants.CACHE_CONVERSATION_CONTEXT,
                cacheKey,
                List.class
        );

        if (cachedRawObjects != null && !cachedRawObjects.isEmpty()) {
            log.debug("Retrieved conversation context from cache - conversationId: {}, raw size: {}",
                    conversationId, cachedRawObjects.size());

            // 【核心修复】手动将 List<Object> (内部是 LinkedHashMap) 转换为 List<Message>
            List<Message> convertedMessages = convertToMessageList(cachedRawObjects);

            if (!convertedMessages.isEmpty()) {
                return limitMessages(convertedMessages, maxMessages);
            }
        }

        // 2. 缓存未命中或转换失败，从数据库获取
        log.debug("Cache miss or conversion failed, fetching from DB - conversationId: {}", conversationId);
        List<Message> messages = messageMapper.selectList(
                new LambdaQueryWrapper<Message>()
                        .eq(Message::getConversationId, conversationId)
                        .orderByDesc(Message::getCreateTime)
                        .last("LIMIT " + Math.max(maxMessages, DEFAULT_WINDOW_SIZE))
        );

        // 确保从数据库获取时包含反馈字段（数据库查询会自动返回所有字段）
        log.debug("Fetched {} messages from DB with feedbackType fields", messages != null ? messages.size() : 0);

        if (messages != null && !messages.isEmpty()) {
            // 回写缓存 (存入实体对象，Redis 序列化器会处理，但读取时仍可能变 Map，所以读取端必须兼容)
            cacheService.set(
                    CacheConfig.CacheConstants.CACHE_CONVERSATION_CONTEXT,
                    cacheKey,
                    messages,
                    CacheConfig.CacheConstants.TTL_CONVERSATION_CONTEXT,
                    TimeUnit.SECONDS
            );
            return limitMessages(messages, maxMessages);
        }

        return new ArrayList<>();
    }


    /**
     * 安全地将缓存中的 Object 列表转换为 Message 实体列表
     * 解决 Redis 读取后变成 LinkedHashMap 导致强转失败的问题
     */
    private List<Message> convertToMessageList(List<Object> rawObjects) {
        List<Message> result = new ArrayList<>();
        for (Object obj : rawObjects) {
            if (obj instanceof Message) {
                // 如果已经是 Message 类型（极少情况，取决于序列化配置）
                result.add((Message) obj);
            } else if (obj instanceof Map) {
                try {
                    // 核心逻辑：使用 ObjectMapper 将 Map 转换为 Message 实体
                    Message message = objectMapper.convertValue(obj, Message.class);
                    result.add(message);
                } catch (IllegalArgumentException e) {
                    log.error("Failed to convert cache map to Message entity: {}", obj, e);
                    // 忽略单个转换错误的消息，防止整个流程崩溃
                }
            } else {
                log.warn("Unexpected object type in conversation cache: {}", obj.getClass());
            }
        }
        return result;
    }




    @Override
    @Transactional
    public void updateConversationContext(Long conversationId, Long userId, Message newMessage) {
        String cacheKey = CacheConfig.CacheConstants.KEY_CONVERSATION_CONTEXT + conversationId;

        // 1. 获取当前上下文
        List<Message> currentContext = getConversationContext(conversationId, MAX_WINDOW_SIZE);

        // 2. 添加新消息（按消息 id 去重）
        //    上方 messageMapper.insert 已提交,此处 getConversationContext 回源查库时
        //    可能已包含该消息;若再无条件追加,缓存会出现同内容两条记录
        //    （时间戳精度不同: DB 整秒 vs 内存带微秒），前端表现为消息重复显示
        List<Message> updatedContext = new ArrayList<>(currentContext);
        boolean alreadyInContext = newMessage.getId() != null && currentContext.stream()
                .anyMatch(m -> Objects.equals(m.getId(), newMessage.getId()));
        if (!alreadyInContext) {
            updatedContext.add(newMessage);
        }

        // 3. 应用滑动窗口
        int windowSize = getContextWindowSize(conversationId);
        if (updatedContext.size() > windowSize) {
            updatedContext = updatedContext.subList(updatedContext.size() - windowSize, updatedContext.size());
        }

        // 4. 更新缓存
        cacheService.set(
                CacheConfig.CacheConstants.CACHE_CONVERSATION_CONTEXT,
                cacheKey,
                updatedContext,
                CacheConfig.CacheConstants.TTL_CONVERSATION_CONTEXT,
                TimeUnit.SECONDS
        );

        // 5. 摘要生成移出事务：提交后再按需触发（详情见 triggerSummaryAfterCommit）
        //    旧实现在事务内全量扫 message 表并写库，每来一条消息都扫一次，事务与行锁被拉长
        triggerSummaryAfterCommit(conversationId);

        // 6. 更新长期记忆（每10条消息或对话结束时）
        if (shouldSaveLongTermMemory(conversationId)) {
            saveLongTermMemory(conversationId, userId);
        }

        log.debug("Updated conversation context - conversationId: {}, new message: {}, context size: {}",
                conversationId, newMessage.getRole(), updatedContext.size());
    }

    @Override
    public String generateConversationSummary(Long conversationId) {
        try {
            // 1. 获取对话历史
            List<Message> messages = messageMapper.selectList(
                    new LambdaQueryWrapper<Message>()
                            .eq(Message::getConversationId, conversationId)
                            .orderByAsc(Message::getCreateTime)
            );

            if (messages.isEmpty()) {
                return "";
            }

            // 2. 构建摘要文本（关键词桩，供第 3 步切换 Python LLM 前的过渡使用）
            //    这里是一份"全量重算"，不合并旧摘要
            String summaryText = buildKeywordSummary(null, messages);

            // 3. 保存到数据库
            //    新行必须 insert：updateById 对 id 为 null 的行影响 0 行且不报错，
            //    表现为"摘要生成了但库里没有"，是最难排查的那类静默失败
            ConversationContext context = getOrCreateContext(conversationId);
            context.setSummary(summaryText);
            context.setSummaryMsgId(messages.get(messages.size() - 1).getId());
            context.setSummaryVersion((context.getSummaryVersion() == null ? 0 : context.getSummaryVersion()) + 1);
            context.setSummaryStatus(SUMMARY_STATUS_READY);
            context.setSummaryUpdateTime(LocalDateTime.now());
            context.setUpdateTime(LocalDateTime.now());
            if (context.getId() == null) {
                conversationContextMapper.insert(context);
            } else {
                conversationContextMapper.updateById(context);
            }
            invalidateSummaryCache(conversationId);

            log.info("Generated conversation summary - conversationId: {}, summary length: {}",
                    conversationId, summaryText.length());

            return summaryText;

        } catch (Exception e) {
            log.error("Failed to generate conversation summary - conversationId: {}", conversationId, e);
            return "";
        }
    }

    /**
     * 摘要生成在事务提交后触发。
     *
     * <p>为什么不能留在事务内：旧的 {@code shouldGenerateSummary} 每次都全量扫 message 表，
     * 事务与行锁被拉长；而异步线程在事务未提交时又读不到刚插入的消息。
     */
    private void triggerSummaryAfterCommit(Long conversationId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    // 这里只负责把任务丢进线程池，不直接做查询/更新：
                    // afterCommit 阶段事务连接仍绑定在当前线程，MyBatis 会复用那条连接，
                    // 写进去不提交、行锁也不释放 —— 表现是状态停在 generating 而任务线程
                    // 等锁直到超时（实测偶发，且只在并发/紧凑执行时出现）
                    submitQuietly(() -> triggerSummaryIfNeeded(conversationId));
                }
            });
        } else {
            triggerSummaryIfNeeded(conversationId);
        }
    }

    private void submitQuietly(Runnable task) {
        try {
            taskExecutor.execute(task);
        } catch (Exception e) {
            // 线程池拒绝：本轮不生成，状态未被抢占，下条消息会重新触发
            log.warn("Failed to submit summary task, will retry on next message", e);
        }
    }

    @Override
    public void triggerSummaryIfNeeded(Long conversationId) {
        boolean claimedOnce = false;
        try {
            ConversationContext context = conversationContextMapper.selectOne(
                    new LambdaQueryWrapper<ConversationContext>()
                            .eq(ConversationContext::getConversationId, conversationId)
            );
            if (context == null) {
                // 上下文行原本要到"每 10 条消息"保存长期记忆时才创建，这里补一次懒创建，
                // 否则游标无处存放，摘要永远等不到第一次生成
                context = new ConversationContext();
                context.setConversationId(conversationId);
                context.setSummaryVersion(0);
                context.setSummaryStatus(SUMMARY_STATUS_NONE);
                context.setWindowSize(DEFAULT_WINDOW_SIZE);
                context.setCreateTime(LocalDateTime.now());
                context.setUpdateTime(LocalDateTime.now());
                try {
                    conversationContextMapper.insert(context);
                } catch (DuplicateKeyException dup) {
                    // 并发下别人先插入了，重查一次即可（uk_conversation_id 保证唯一）
                    context = conversationContextMapper.selectOne(
                            new LambdaQueryWrapper<ConversationContext>()
                                    .eq(ConversationContext::getConversationId, conversationId)
                    );
                    if (context == null) {
                        return;
                    }
                }
            }

            long baseMsgId = context.getSummaryMsgId() == null ? 0L : context.getSummaryMsgId();
            Long pending = messageMapper.selectCount(
                    new LambdaQueryWrapper<Message>()
                            .eq(Message::getConversationId, conversationId)
                            .gt(Message::getId, baseMsgId)
            );
            if (pending == null || pending < SUMMARY_TRIGGER_MIN_NEW_MESSAGES) {
                return;
            }

            // CAS 抢占：只有把 status 从「非 generating」改成 generating 的那一次才继续执行，
            // 并发场景下其余调用的影响行数为 0，直接放弃 —— 同一会话同时只跑一个任务
            LocalDateTime staleBefore = LocalDateTime.now().minusMinutes(SUMMARY_STALE_MINUTES);
            int claimed = conversationContextMapper.update(null,
                    new LambdaUpdateWrapper<ConversationContext>()
                            .eq(ConversationContext::getConversationId, conversationId)
                            .and(w -> w.ne(ConversationContext::getSummaryStatus, SUMMARY_STATUS_GENERATING)
                                    .or().isNull(ConversationContext::getSummaryStatus)
                                    // 卡死恢复：update_time 会在每次 UPDATE 时自动刷新，
                                    // 抢占后超过 10 分钟仍未完成，说明任务已死（进程重启/线程丢失），
                                    // 否则该会话会永久停在 generating
                                    .or().lt(ConversationContext::getUpdateTime, staleBefore))
                            .set(ConversationContext::getSummaryStatus, SUMMARY_STATUS_GENERATING)
                            // 必须顺带刷新一个时间列：MySQL 对「所有列都赋成原值」的 UPDATE
                            // 返回 0 行，而卡死恢复场景下 status 本来就是 generating，
                            // 只改 status 会被当成"没抢到"，卡死状态永远解不开
                            .set(ConversationContext::getSummaryUpdateTime, LocalDateTime.now())
            );
            if (claimed == 0) {
                log.debug("Summary task already running, skip - conversationId: {}", conversationId);
                return;
            }
            claimedOnce = true;

            taskExecutor.execute(() -> runSummaryTask(conversationId));
        } catch (Exception e) {
            // 摘要只是上下文补充，触发失败不能影响消息落库（本方法在事务提交后执行）——
            // 但"没生成"必须在数据上留痕：只写日志的话，翻日志才知道这个会话的摘要已经
            // 停在很早的位置，管理端完全看不出来。失败的会话可被下次触发重新抢占。
            log.warn("Failed to trigger summary, this round is skipped - conversationId: {}",
                    conversationId, e);
            if (claimedOnce) {
                // 只有已经抢到锁才需要归还，否则会把别人的 generating 状态覆盖成 failed
                resetSummaryStatus(conversationId, SUMMARY_STATUS_FAILED);
            }
        }
    }

    private void resetSummaryStatus(Long conversationId, String status) {
        try {
            conversationContextMapper.update(null,
                    new LambdaUpdateWrapper<ConversationContext>()
                            .eq(ConversationContext::getConversationId, conversationId)
                            .set(ConversationContext::getSummaryStatus, status)
            );
        } catch (Exception e) {
            log.warn("Failed to reset summary status - conversationId: {}, status: {}",
                    conversationId, status, e);
        }
    }

    /**
     * 异步任务本体：只处理游标之后的消息（增量），成功后推进游标并失效 Redis 缓存。
     * 失败时保留旧摘要，仅把状态记为 failed，下次触发会重新抢占覆盖。
     */
    private void runSummaryTask(Long conversationId) {
        try {
            ConversationContext context = conversationContextMapper.selectOne(
                    new LambdaQueryWrapper<ConversationContext>()
                            .eq(ConversationContext::getConversationId, conversationId)
            );
            if (context == null) {
                return;
            }

            long baseMsgId = context.getSummaryMsgId() == null ? 0L : context.getSummaryMsgId();
            List<Message> messages = messageMapper.selectList(
                    new LambdaQueryWrapper<Message>()
                            .eq(Message::getConversationId, conversationId)
                            .gt(Message::getId, baseMsgId)
                            .orderByAsc(Message::getId)
                            .last("LIMIT " + SUMMARY_BATCH_LIMIT)
            );

            if (messages.isEmpty()) {
                resetSummaryStatus(conversationId, SUMMARY_STATUS_NONE);
                return;
            }

            // 优先交给 Python LLM（必须带旧摘要做合并）；不可用/超时/返回非法时降级到本地关键词桩。
            // 降级也照常推进游标并落库 —— 少内容比"这段对话彻底没摘要"好，
            // 状态记 failed 只是为了能在数据上看出"这份是降级产物"，不是"没生成"。
            Optional<String> llmSummary = summaryGenerator.summarize(
                    conversationId, context.getSummary(), messages);
            boolean degraded = llmSummary.isEmpty();
            String summaryText = llmSummary.orElseGet(
                    () -> buildKeywordSummary(context.getSummary(), messages));

            Long lastMsgId = messages.get(messages.size() - 1).getId();
            int nextVersion = (context.getSummaryVersion() == null ? 0 : context.getSummaryVersion()) + 1;

            conversationContextMapper.update(null,
                    new LambdaUpdateWrapper<ConversationContext>()
                            .eq(ConversationContext::getConversationId, conversationId)
                            .set(ConversationContext::getSummary, summaryText)
                            .set(ConversationContext::getSummaryMsgId, lastMsgId)
                            .set(ConversationContext::getSummaryVersion, nextVersion)
                            .set(ConversationContext::getSummaryStatus,
                                    degraded ? SUMMARY_STATUS_FAILED : SUMMARY_STATUS_READY)
                            .set(ConversationContext::getSummaryUpdateTime, LocalDateTime.now())
                            .set(ConversationContext::getUpdateTime, LocalDateTime.now())
            );

            invalidateSummaryCache(conversationId);

            log.info("Summary generated{} - conversationId: {}, covered: {} messages, upToMsgId: {}, version: {}",
                    degraded ? " (degraded to keyword)" : " by LLM",
                    conversationId, messages.size(), lastMsgId, nextVersion);
        } catch (Exception e) {
            log.error("Summary task failed - conversationId: {}", conversationId, e);
            resetSummaryStatus(conversationId, SUMMARY_STATUS_FAILED);
        }
    }

    private void invalidateSummaryCache(Long conversationId) {
        try {
            stringRedisTemplate.delete(summaryCacheKey(conversationId));
        } catch (Exception e) {
            log.warn("Failed to invalidate summary cache - conversationId: {}", conversationId, e);
        }
    }

    /**
     * 关键词摘要桩：第 3 步切到 Python LLM 之前的主路径，LLM 不可用时也是兜底。
     *
     * @param oldSummary 上一版摘要，增量场景必须传入，否则早期内容会被整段丢弃；
     *                   全量重算（generateConversationSummary）传 null
     */
    private String buildKeywordSummary(String oldSummary, List<Message> messages) {
        StringBuilder summary = new StringBuilder();

        // 旧摘要只保留尾部：关键词桩的合并很粗糙（同类标签会重复出现），
        // 但"丢历史"比"重复"严重得多，第 3 步换成 LLM 合并后这里只剩兜底作用
        if (oldSummary != null && !oldSummary.isBlank()) {
            String previous = oldSummary.trim();
            if (previous.length() > SUMMARY_CARRY_CHARS) {
                previous = previous.substring(previous.length() - SUMMARY_CARRY_CHARS);
            }
            summary.append(previous).append("\n");
        }

        Set<String> topics = new HashSet<>();
        List<String> decisions = new ArrayList<>();
        Map<String, Integer> userPreferences = new HashMap<>();

        for (Message message : messages) {
            if (message.getContent() == null) {
                continue;
            }
            if ("user".equals(message.getRole())) {
                // 提取用户问题中的关键词
                extractKeywords(message.getContent(), topics);
                // 识别用户偏好
                detectUserPreferences(message.getContent(), userPreferences);
            } else if ("assistant".equals(message.getRole())) {
                // 识别AI的建议和决策
                detectDecisions(message.getContent(), decisions);
            }
        }

        summary.append("关键话题: ").append(String.join("、", topics)).append("\n");
        if (!decisions.isEmpty()) {
            summary.append("关键决策: ").append(String.join("；", decisions)).append("\n");
        }
        if (!userPreferences.isEmpty()) {
            summary.append("用户偏好: ");
            userPreferences.forEach((key, count) ->
                    summary.append(key).append("(").append(count).append("次) "));
            summary.append("\n");
        }

        // 总长兜底：超长直接截尾，避免把 Prompt 预算吃光
        String text = summary.toString();
        if (text.length() > SUMMARY_MAX_CHARS) {
            text = text.substring(text.length() - SUMMARY_MAX_CHARS);
        }
        return text;
    }

    /**
     * 摘要缺失时的同步兜底：只用关键词桩（毫秒级），绝不在这里调 LLM。
     *
     * <p>阈值与异步触发保持一致，避免 2 条消息的会话也生成一份没意义的摘要。
     */
    private String backfillSummaryIfNeeded(Long conversationId) {
        try {
            Long total = messageMapper.selectCount(
                    new LambdaQueryWrapper<Message>()
                            .eq(Message::getConversationId, conversationId)
            );
            if (total == null || total < SUMMARY_TRIGGER_MIN_NEW_MESSAGES) {
                return null;
            }

            String summary = generateConversationSummary(conversationId);
            if (summary == null || summary.isBlank()) {
                return null;
            }
            log.info("Summary backfilled by keyword stub - conversationId: {}, messages: {}",
                    conversationId, total);
            return summary;
        } catch (Exception e) {
            log.warn("Failed to backfill summary - conversationId: {}", conversationId, e);
            return null;
        }
    }

    @Override
    public String getConversationSummary(Long conversationId) {
        // 与 getConversationSummaryIfPresent 共用读取路径，避免同一份摘要有两个数据来源
        String existing = getConversationSummaryIfPresent(conversationId);
        if (existing != null && !existing.isBlank()) {
            return existing;
        }

        // 还没有摘要，现场生成一次（兜底路径；第 3 步改由 Python 异步生成后此路径退居二线）
        return generateConversationSummary(conversationId);
    }

    @Override
    public String getConversationSummaryIfPresent(Long conversationId) {
        // 读取路径收敛：Redis 缓存 → miss 回源 MySQL → 回填缓存
        // 注：直连 StringRedisTemplate 而非 CacheService —— 摘要就是一段纯文本，
        // 走 CacheService 会被套上 conv:ctx: 前缀并用 Jackson 序列化，读出来必然对不上
        String cacheKey = summaryCacheKey(conversationId);
        try {
            String cached = stringRedisTemplate.opsForValue().get(cacheKey);
            if (cached != null && !cached.isBlank()) {
                return cached;
            }
        } catch (Exception e) {
            // Redis 只是缓存，不可用时继续回源 MySQL
            log.warn("Failed to read summary cache - conversationId: {}", conversationId, e);
        }

        try {
            ConversationContext context = conversationContextMapper.selectOne(
                    new LambdaQueryWrapper<ConversationContext>()
                            .eq(ConversationContext::getConversationId, conversationId)
            );
            if (context == null || context.getSummary() == null || context.getSummary().isBlank()) {
                // 空窗兜底：从未生成过摘要时用关键词桩同步补一份（毫秒级，不调 LLM）。
                // 删掉 Python 侧摘要生成后，早期对话只能靠 MySQL 摘要承接，
                // 而摘要是异步生成的 —— 首个任务完成前，这段内容两边都没有
                return backfillSummaryIfNeeded(conversationId);
            }
            String summary = context.getSummary();

            // 回填缓存；失败只影响下一次命中率，不影响本次返回
            try {
                stringRedisTemplate.opsForValue()
                        .set(cacheKey, summary, SUMMARY_CACHE_TTL_SECONDS, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("Failed to backfill summary cache - conversationId: {}", conversationId, e);
            }
            return summary;
        } catch (Exception e) {
            // 摘要只是上下文补充，读取失败不应影响问答主流程
            log.warn("Failed to read conversation summary - conversationId: {}", conversationId, e);
            return null;
        }
    }

    private String summaryCacheKey(Long conversationId) {
        return SUMMARY_CACHE_PREFIX + conversationId;
    }

    @Override
    @Transactional
    public void saveLongTermMemory(Long conversationId, Long userId) {
        try {
            // 1. 获取或创建上下文记录
            ConversationContext context = getOrCreateContext(conversationId);
            context.setUserId(userId);

            // 2. 生成或更新摘要
            if (context.getSummary() == null) {
                String summary = generateConversationSummary(conversationId);
                context.setSummary(summary);
            }

            // 3. 计算重要性评分
            List<Message> messages = messageMapper.selectList(
                    new LambdaQueryWrapper<Message>()
                            .eq(Message::getConversationId, conversationId)
            );

            double importanceScore = calculateConversationImportance(messages);
            context.setImportanceScore(importanceScore);

            // 4. 设置窗口大小（根据重要性动态调整）
            int windowSize = calculateOptimalWindowSize(importanceScore, messages.size());
            context.setWindowSize(windowSize);

            // 5. 保存到数据库
            //    摘要的三个状态字段归异步任务维护，这里用的是旧快照，
            //    updateById 会把它们写回查询时的旧值，等于把任务刚推进的游标/版本回滚掉
            context.setSummaryMsgId(null);
            context.setSummaryVersion(null);
            context.setSummaryStatus(null);
            context.setSummaryUpdateTime(null);

            context.setUpdateTime(LocalDateTime.now());
            if (context.getId() == null) {
                context.setCreateTime(LocalDateTime.now());
                conversationContextMapper.insert(context);
            } else {
                conversationContextMapper.updateById(context);
            }

            // SLF4J 只认 {}，写 {:.2f} 会被当字面量原样打印
            log.info("Saved long-term memory - conversationId: {}, userId: {}, importance: {}",
                    conversationId, userId, String.format("%.2f", importanceScore));

        } catch (Exception e) {
            log.error("Failed to save long-term memory - conversationId: {}, userId: {}",
                    conversationId, userId, e);
        }
    }

    @Override
    public List<ConversationContext> findSimilarConversations(Long userId, String query, int topK) {
        // 简化版：按关键词匹配
        // 实际应该使用向量搜索（Milvus/FAISS）

        List<ConversationContext> contexts = conversationContextMapper.selectList(
                new LambdaQueryWrapper<ConversationContext>()
                        .eq(ConversationContext::getUserId, userId)
                        .orderByDesc(ConversationContext::getImportanceScore)
                        .last("LIMIT " + topK)
        );

        // 简单的文本相似度匹配
        return contexts.stream()
                .filter(context -> {
                    if (context.getSummary() == null) return false;
                    // 检查摘要中是否包含查询关键词
                    String[] keywords = query.toLowerCase().split("\\s+");
                    String summary = context.getSummary().toLowerCase();
                    return Arrays.stream(keywords)
                            .anyMatch(summary::contains);
                })
                .limit(topK)
                .collect(Collectors.toList());
    }

    @Override
    public void cleanupExpiredContexts(int days) {
        LocalDateTime cutoffDate = LocalDateTime.now().minusDays(days);

        // 删除过期的上下文记录（保留重要对话）
        int deleted = conversationContextMapper.delete(
                new LambdaQueryWrapper<ConversationContext>()
                        .lt(ConversationContext::getUpdateTime, cutoffDate)
                        .lt(ConversationContext::getImportanceScore, 0.3) // 低重要性
        );

        log.info("Cleaned up expired contexts - deleted: {}, days: {}", deleted, days);
    }

    @Override
    public int getContextWindowSize(Long conversationId) {
        ConversationContext context = conversationContextMapper.selectOne(
                new LambdaQueryWrapper<ConversationContext>()
                        .eq(ConversationContext::getConversationId, conversationId)
        );

        if (context != null && context.getWindowSize() != null) {
            return Math.min(Math.max(context.getWindowSize(), MIN_WINDOW_SIZE), MAX_WINDOW_SIZE);
        }

        return DEFAULT_WINDOW_SIZE;
    }

    @Override
    public void adjustContextWindowSize(Long conversationId, int windowSize) {
        // 确保窗口大小在合理范围内
        int adjustedSize = Math.min(Math.max(windowSize, MIN_WINDOW_SIZE), MAX_WINDOW_SIZE);

        ConversationContext context = getOrCreateContext(conversationId);
        context.setWindowSize(adjustedSize);
        context.setUpdateTime(LocalDateTime.now());

        if (context.getId() == null) {
            conversationContextMapper.insert(context);
        } else {
            conversationContextMapper.updateById(context);
        }

        log.debug("Adjusted context window size - conversationId: {}, windowSize: {}",
                conversationId, adjustedSize);
    }

    @Override
    public double calculateMessageImportance(Message message) {
        // 简化的重要性计算
        double importance = 0.5; // 基础分数

        // 1. 内容长度（越长可能越重要）
        if (message.getContent() != null) {
            int length = message.getContent().length();
            if (length > 100) importance += 0.2;
            else if (length > 50) importance += 0.1;
        }

        // 2. 是否有来源（有来源的回答更重要）
        if (message.getSources() != null && !message.getSources().isEmpty()) {
            importance += 0.2;
        }

        // 3. 用户消息 vs AI消息
        if ("user".equals(message.getRole())) {
            importance += 0.1; // 用户消息稍微重要一些
        }

        // 4. 包含关键词
        String[] importantKeywords = {"重要", "关键", "必须", "紧急", "决定", "选择"};
        if (message.getContent() != null) {
            String content = message.getContent().toLowerCase();
            for (String keyword : importantKeywords) {
                if (content.contains(keyword)) {
                    importance += 0.1;
                    break;
                }
            }
        }

        return Math.min(importance, 1.0);
    }

    @Override
    public int compressConversationHistory(Long conversationId) {
        List<Message> messages = messageMapper.selectList(
                new LambdaQueryWrapper<Message>()
                        .eq(Message::getConversationId, conversationId)
                        .orderByAsc(Message::getCreateTime)
        );

        if (messages.size() <= MAX_WINDOW_SIZE) {
            return messages.size(); // 无需压缩
        }

        // 计算每条消息的重要性
        Map<Long, Double> importanceScores = new HashMap<>();
        for (Message message : messages) {
            importanceScores.put(message.getId(), calculateMessageImportance(message));
        }

        // 按重要性排序，保留最重要的消息
        List<Message> importantMessages = messages.stream()
                .sorted((m1, m2) -> {
                    double score1 = importanceScores.getOrDefault(m1.getId(), 0.0);
                    double score2 = importanceScores.getOrDefault(m2.getId(), 0.0);
                    return Double.compare(score2, score1); // 降序
                })
                .limit(MAX_WINDOW_SIZE)
                .sorted(Comparator.comparing(Message::getCreateTime)) // 恢复时间顺序
                .collect(Collectors.toList());

        // 这里可以添加逻辑来删除不重要的消息
        // 但为了数据完整性，我们只标记而不删除

        log.info("Compressed conversation history - conversationId: {}, original: {}, compressed: {}",
                conversationId, messages.size(), importantMessages.size());

        return importantMessages.size();
    }

    // ========== 私有辅助方法 ==========

    private List<Message> limitMessages(List<Message> messages, int maxMessages) {
        if (messages == null || messages.size() <= maxMessages) {
            return messages != null ? messages : Collections.emptyList();
        }
        return messages.subList(messages.size() - maxMessages, messages.size());
    }

    private ConversationContext getOrCreateContext(Long conversationId) {
        ConversationContext context = conversationContextMapper.selectOne(
                new LambdaQueryWrapper<ConversationContext>()
                        .eq(ConversationContext::getConversationId, conversationId)
        );

        if (context == null) {
            context = new ConversationContext();
            context.setConversationId(conversationId);
            context.setWindowSize(DEFAULT_WINDOW_SIZE);
            context.setCreateTime(LocalDateTime.now());
            context.setUpdateTime(LocalDateTime.now());
        }

        return context;
    }

    private boolean shouldSaveLongTermMemory(Long conversationId) {
        // 每10条消息保存一次长期记忆。
        // 用 count 而不是 selectList：原来每来一条消息就把整段会话拉进内存，只为取一个取模
        Long total = messageMapper.selectCount(
                new LambdaQueryWrapper<Message>()
                        .eq(Message::getConversationId, conversationId)
        );

        return total != null && total > 0 && total % 10 == 0;
    }

    private void extractKeywords(String content, Set<String> topics) {
        // 简化的关键词提取
        String[] commonTopics = {"项目", "技术", "代码", "问题", "方案", "设计", "测试", "部署"};
        String lowerContent = content.toLowerCase();

        for (String topic : commonTopics) {
            if (lowerContent.contains(topic.toLowerCase())) {
                topics.add(topic);
            }
        }
    }

    private void detectUserPreferences(String content, Map<String, Integer> preferences) {
        // 简化的偏好检测
        String[] preferenceKeywords = {"喜欢", "偏好", "常用", "习惯", "希望", "想要"};

        for (String keyword : preferenceKeywords) {
            if (content.contains(keyword)) {
                preferences.put(keyword, preferences.getOrDefault(keyword, 0) + 1);
            }
        }
    }

    private void detectDecisions(String content, List<String> decisions) {
        // 简化的决策检测
        String[] decisionKeywords = {"建议", "推荐", "选择", "决定", "方案", "计划"};

        for (String keyword : decisionKeywords) {
            if (content.contains(keyword)) {
                // 提取包含关键词的句子
                String[] sentences = content.split("[。！？]");
                for (String sentence : sentences) {
                    if (sentence.contains(keyword) && sentence.length() > 10) {
                        decisions.add(sentence.trim());
                        break;
                    }
                }
            }
        }
    }

    private double calculateConversationImportance(List<Message> messages) {
        if (messages.isEmpty()) {
            return 0.0;
        }

        double totalImportance = 0.0;
        int importantCount = 0;

        for (Message message : messages) {
            double importance = calculateMessageImportance(message);
            totalImportance += importance;

            if (importance > 0.7) {
                importantCount++;
            }
        }

        double avgImportance = totalImportance / messages.size();

        // 重要性计算：平均重要性 + 重要消息比例
        double importanceScore = avgImportance * 0.7 + (importantCount / (double) messages.size()) * 0.3;

        return Math.min(importanceScore, 1.0);
    }

    private int calculateOptimalWindowSize(double importanceScore, int messageCount) {
        // 根据对话重要性和消息数量动态计算窗口大小
        int baseSize = DEFAULT_WINDOW_SIZE;

        // 重要性越高，窗口越大（保留更多上下文）
        if (importanceScore > 0.8) {
            baseSize = MAX_WINDOW_SIZE; // 重要对话保留最大窗口
        } else if (importanceScore > 0.5) {
            baseSize = DEFAULT_WINDOW_SIZE + 5; // 中等重要性对话增加窗口
        }

        // 消息数量较少时，适当减小窗口
        if (messageCount < baseSize) {
            return Math.max(messageCount, MIN_WINDOW_SIZE);
        }

        return Math.min(baseSize, MAX_WINDOW_SIZE);
    }
}