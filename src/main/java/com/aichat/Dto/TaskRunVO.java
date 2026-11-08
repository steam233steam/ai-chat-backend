package com.aichat.Dto;

import java.time.LocalDateTime;

/**
 * 任务执行记录的单行展示（闹钟响过几次、成没成）。
 */
public record TaskRunVO(Long id,
        Long taskId,
        String actionType,
        String triggerType,
        String status,
        String detail,
        LocalDateTime startedAt,
        LocalDateTime finishedAt) {
}
