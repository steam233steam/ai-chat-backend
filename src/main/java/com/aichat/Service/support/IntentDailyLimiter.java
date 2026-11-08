package com.aichat.Service.support;

import java.time.Clock;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.aichat.Config.MindButlerProperties;

/**
 * 每用户每日意图提取限流。计数逻辑见 {@link DailyRateLimiter}。
 *
 * <p>`app.mindbutler.intent.daily-limit <= 0` 表示不限。
 * 放在 Service.support 而不是 Service 根包：Service 根包只放接口。
 */
@Component
public class IntentDailyLimiter {

    private static final String PREFIX = "mindbutler:intent:daily:";

    private final MindButlerProperties props;
    private final Clock clock;
    private final StringRedisTemplate redis;

    // 延迟创建：单测会用反射替换 clock 字段，等替换完再建才不会用上旧时钟
    private DailyRateLimiter delegate;

    public IntentDailyLimiter(MindButlerProperties props, Clock clock, StringRedisTemplate redis) {
        this.props = props;
        this.clock = clock;
        this.redis = redis;
    }

    public boolean tryAcquire(String userId) {
        return delegate().tryAcquire(userId, props.getIntentDailyLimit());
    }

    private synchronized DailyRateLimiter delegate() {
        if (delegate == null) {
            delegate = new DailyRateLimiter(clock, redis, PREFIX);
        }
        return delegate;
    }
}
