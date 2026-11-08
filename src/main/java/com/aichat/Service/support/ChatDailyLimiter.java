package com.aichat.Service.support;

import java.time.Clock;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 每用户每日对话次数上限，防止一个账号把模型额度刷完。
 *
 * <p>`app.chat.daily-limit <= 0` 表示不限（默认），本地开发不用配。
 */
@Component
public class ChatDailyLimiter {

    private static final String PREFIX = "aichat:chat:daily:";

    private final DailyRateLimiter limiter;
    private final int limit;

    public ChatDailyLimiter(@Value("${app.chat.daily-limit:0}") int limit,
            Clock clock,
            StringRedisTemplate redis) {
        this.limit = limit;
        this.limiter = new DailyRateLimiter(clock, redis, PREFIX);
    }

    public boolean tryAcquire(String userId) {
        return limiter.tryAcquire(userId, limit);
    }
}
