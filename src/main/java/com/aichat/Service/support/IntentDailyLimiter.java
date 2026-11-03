package com.aichat.Service.support;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.aichat.Config.MindButlerProperties;

/**
 * 每用户每日意图提取限流。
 *
 * 优先 Redis（多实例一致，key 按 user+日期，TTL 2 天）；Redis 不可用时降级到进程内计数。
 * `app.mindbutler.intent.daily-limit <= 0` 表示不限。
 *
 * 放在 Service.support 而不是 Service 根包：Service 根包只放接口，避免“接口和组件混在一起”。
 */
@Component
public class IntentDailyLimiter {
    private static final Logger log = LoggerFactory.getLogger(IntentDailyLimiter.class);
    private static final String PREFIX = "mindbutler:intent:daily:";

    @Autowired
    private MindButlerProperties props;
    @Autowired
    private Clock clock;

    /** Redis 不可用时兜底；不引入新依赖。 */
    @Autowired(required = false)
    private StringRedisTemplate redis;

    private final ConcurrentHashMap<String, AtomicInteger> localCounts = new ConcurrentHashMap<>();

    public boolean tryAcquire(String userId) {
        int limit = props.getIntentDailyLimit();
        if (limit <= 0 || userId == null || userId.isBlank()) {
            return true;
        }
        String day = LocalDate.now(clock).toString();
        String key = PREFIX + userId + ":" + day;

        if (redis != null) {
            try {
                Long count = redis.opsForValue().increment(key);
                if (count != null && count == 1L) {
                    redis.expire(key, Duration.ofDays(2));
                }
                if (count != null) {
                    return count <= limit;
                }
            } catch (Exception e) {
                log.warn("意图限流 Redis 不可用，降级进程内计数: {}", e.getMessage());
            }
        }

        // 防止长期运行累积历史日期 key
        localCounts.keySet().removeIf(k -> !k.endsWith(day));
        int count = localCounts.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
        return count <= limit;
    }
}
