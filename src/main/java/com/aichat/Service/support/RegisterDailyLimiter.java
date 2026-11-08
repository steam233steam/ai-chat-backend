package com.aichat.Service.support;

import java.time.Clock;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 每个来源 IP 每日注册次数上限。
 *
 * <p>线上没接短信服务，注册只能靠手机号 + 密码，所以这道闸门是防批量刷号的唯一手段：
 * <code>app.register.daily-limit &lt;= 0</code> 表示不限（仅本地开发这么用）。
 */
@Component
public class RegisterDailyLimiter {

    private static final String PREFIX = "aichat:register:daily:";

    private final DailyRateLimiter limiter;
    private final int limit;

    public RegisterDailyLimiter(@Value("${app.register.daily-limit:5}") int limit,
            Clock clock,
            StringRedisTemplate redis) {
        this.limit = limit;
        this.limiter = new DailyRateLimiter(clock, redis, PREFIX);
    }

    public boolean tryAcquire(String clientIp) {
        // 拿不到 IP 时不拦（宁可放过，也不要因为反代配置把正常用户挡死）
        if (clientIp == null || clientIp.isBlank()) {
            return true;
        }
        return limiter.tryAcquire(clientIp, limit);
    }
}
