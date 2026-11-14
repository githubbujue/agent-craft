package com.demo.aiknowledge.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 摘要任务专用线程池。
 *
 * <p>为什么不复用 Spring Boot 自动配置的 applicationTaskExecutor：SSE 流式转发也跑在那个池上，
 * 摘要任务一旦引入 LLM 调用（10s 超时可以等满），会把转发线程占住，用户侧表现为"回答卡住"。
 *
 * <p>拒绝策略用 AbortPolicy 而不是丢弃：丢弃会让状态停在 generating（调用方拿不到拒绝信号，
 * 无法回滚）。Abort 会抛异常，调用方 catch 后把状态记 failed，下条消息可重试。
 */
@Configuration
public class SummaryExecutorConfig {

    @Bean("summaryTaskExecutor")
    public ThreadPoolTaskExecutor summaryTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("summary-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        // 停机不等待未完成任务：摘要可以下次重算，没必要拖慢滚动发布
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }
}
