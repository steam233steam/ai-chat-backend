package com.aichat.Config;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 异步任务配置。
 *
 * <p>会话标题生成是「可延迟、可失败」的旁路任务，放在独立线程池里执行，
 * 避免占用 Tomcat 请求线程；队列满时回退到调用线程执行（CallerRunsPolicy），
 * 宁可慢一点，也不静默丢弃标题任务。
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    /** 会话标题生成专用线程池的 Bean 名称 */
    public static final String CHAT_TITLE_EXECUTOR = "chatTitleExecutor";

    @Bean(name = CHAT_TITLE_EXECUTOR)
    public Executor chatTitleExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("chat-title-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }
}
