package com.aichat.Service.support;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 按「主体 + 自然日」计数的限流器，意图提取和聊天共用。
 *
 * <p>优先 Redis（多实例下计数一致，key 带日期、TTL 2 天）；Redis 不可用时降级为进程内计数，
 * 只保证单实例正确。<code>limit &lt;= 0</code> 表示不限。
 */
public class DailyRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(DailyRateLimiter.class);

    private final Clock clock;
    private final StringRedisTemplate redis;
    private final String prefix;
    private final ConcurrentHashMap<String, AtomicInteger> localCounts = new ConcurrentHashMap<>();

    public DailyRateLimiter(Clock clock, StringRedisTemplate redis, String prefix) {
        this.clock = clock;
        this.redis = redis;
        this.prefix = prefix;
    }

    public boolean tryAcquire(String subject, int limit) {
        if (limit <= 0 || subject == null || subject.isBlank()) {
            return true;
        }
        String day = LocalDate.now(clock).toString();
        String key = prefix + subject + ":" + day;

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
                log.warn("限流 Redis 不可用，降级进程内计数: {}", e.getMessage());
            }
        }

        // 防止长期运行累积历史日期 key
        localCounts.keySet().removeIf(k -> !k.endsWith(day));
        int count = localCounts.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
        return count <= limit;
    }
}
