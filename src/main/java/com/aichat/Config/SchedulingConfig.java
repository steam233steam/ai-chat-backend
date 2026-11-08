package com.aichat.Config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 打开定时任务能力（目前只有任务闹钟在用）。
 * 具体要不要跑由 app.task.alarm.enabled 决定，装配本身不做条件。
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
