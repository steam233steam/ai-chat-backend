package com.aichat.Config;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 全局可注入的 Clock，固定 P1 时区（Asia/Shanghai），替代散落的 LocalDateTime.now()。
 * 测试可固定时钟做确定性断言；P1.5 多时区时再把这里改成按用户时区。
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock(@Value("${app.mindbutler.task.zone:Asia/Shanghai}") String zone) {
        return Clock.system(ZoneId.of(zone));
    }
}
