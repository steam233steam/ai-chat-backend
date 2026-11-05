package com.aichat.Config;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 意图提取专用线程池：LLM 调用可能卡住，必须有独立超时，不能占满 Tomcat 请求线程。
 * 线程数固定 2，daemon，应用关闭时 shutdownNow。
 */
@Configuration
public class IntentExecutorConfig {

    public static final String INTENT_EXECUTOR = "intentExecutor";

    @Bean(name = INTENT_EXECUTOR, destroyMethod = "shutdownNow")
    public ExecutorService intentExecutor() {
        AtomicInteger seq = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "intent-extractor-" + seq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        // 有界队列：模型卡死/超时后底层调用若没有及时释放线程，不能无限堆积任务把内存吃满。
        // 队列满时 submit 抛 RejectedExecutionException，IntentExtractor 会降级为空候选。
        return new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(16), factory, new ThreadPoolExecutor.AbortPolicy());
    }
}
