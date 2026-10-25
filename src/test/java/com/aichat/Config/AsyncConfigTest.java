package com.aichat.Config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.scheduling.annotation.Async;

/**
 * 验证 @EnableAsync 和命名线程池真的生效，而不是只写了注解。
 */
class AsyncConfigTest {

    @Test
    void shouldRunOnChatTitleExecutor() throws Exception {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(AsyncConfig.class, AsyncProbe.class);
            context.refresh();

            AsyncProbe probe = context.getBean(AsyncProbe.class);
            probe.run();

            assertThat(probe.await()).isTrue();
            assertThat(probe.threadName()).startsWith("chat-title-");
        }
    }

    public static class AsyncProbe {
        private final CountDownLatch latch = new CountDownLatch(1);
        private volatile String threadName;

        @Async(AsyncConfig.CHAT_TITLE_EXECUTOR)
        public void run() {
            threadName = Thread.currentThread().getName();
            latch.countDown();
        }

        boolean await() throws InterruptedException {
            return latch.await(3, TimeUnit.SECONDS);
        }

        String threadName() {
            return threadName;
        }
    }
}
