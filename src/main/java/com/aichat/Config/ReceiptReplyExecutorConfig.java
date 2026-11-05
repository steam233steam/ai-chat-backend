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
 * 人设化回执生成专用线程池：确认/取消回执可以走一次短 LLM 调用，
 * 但必须有独立超时和有限队列，不能拖住确认接口或无限堆积。
 */
@Configuration
public class ReceiptReplyExecutorConfig {

    public static final String RECEIPT_EXECUTOR = "receiptReplyExecutor";

    @Bean(name = RECEIPT_EXECUTOR, destroyMethod = "shutdownNow")
    public ExecutorService receiptReplyExecutor() {
        AtomicInteger seq = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "receipt-reply-" + seq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        return new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(8), factory, new ThreadPoolExecutor.AbortPolicy());
    }
}
