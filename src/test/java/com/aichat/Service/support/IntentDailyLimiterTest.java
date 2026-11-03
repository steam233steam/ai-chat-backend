package com.aichat.Service.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.aichat.Config.MindButlerProperties;

class IntentDailyLimiterTest {

    private IntentDailyLimiter limiter;

    @BeforeEach
    void setUp() {
        MindButlerProperties props = new MindButlerProperties();
        props.setIntentDailyLimit(2);
        limiter = new IntentDailyLimiter();
        ReflectionTestUtils.setField(limiter, "props", props);
        ReflectionTestUtils.setField(limiter, "clock",
                Clock.fixed(Instant.parse("2026-11-03T00:00:00Z"), ZoneId.of("Asia/Shanghai")));
    }

    @Test
    void shouldEnforceDailyLimitPerUser() {
        assertThat(limiter.tryAcquire("u1")).isTrue();
        assertThat(limiter.tryAcquire("u1")).isTrue();
        assertThat(limiter.tryAcquire("u1")).isFalse();
        assertThat(limiter.tryAcquire("u2")).isTrue();
    }
}
