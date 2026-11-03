package com.aichat.Service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 只测超时外壳：不依赖真实 LLM，验证慢调用会被截断、快调用正常返回。
 */
class IntentExtractorimplTest {

    private ExecutorService executor;
    private IntentExtractorimpl extractor;

    @BeforeEach
    void setUp() {
        executor = Executors.newSingleThreadExecutor();
        extractor = new IntentExtractorimpl();
        ReflectionTestUtils.setField(extractor, "intentExecutor", executor);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void shouldTimeoutSlowCall() {
        long start = System.currentTimeMillis();
        String result = extractor.callWithTimeout(() -> {
            Thread.sleep(500);
            return "{}";
        }, 50);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(result).isNull();
        assertThat(elapsed).isLessThan(400);
    }

    @Test
    void shouldReturnFastCallResult() {
        assertThat(extractor.callWithTimeout(() -> "ok", 1000)).isEqualTo("ok");
    }
}
