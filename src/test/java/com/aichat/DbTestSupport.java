package com.aichat;

import org.junit.jupiter.api.Assumptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 集成测试基建可用性探测：本机没有起 MySQL/Redis 时跳过，而不是让 `mvn test` 报 24 个连接错误。
 * 真正的端到端验收仍可在虚拟机启动后显式运行（见 README）。
 */
final class DbTestSupport {

    private DbTestSupport() {
    }

    static void assumeMysqlAvailable(JdbcTemplate jdbc) {
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
        } catch (Exception e) {
            Assumptions.assumeTrue(false, "MySQL 不可用，跳过集成测试: " + e.getMessage());
        }
    }

    static void assumeRedisAvailable(StringRedisTemplate redis) {
        var connection = redis.getConnectionFactory().getConnection();
        try {
            connection.ping();
        } catch (Exception e) {
            Assumptions.assumeTrue(false, "Redis 不可用，跳过集成测试: " + e.getMessage());
        } finally {
            connection.close();
        }
    }
}
