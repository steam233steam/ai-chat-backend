package com.aichat.Dto;

import java.time.LocalDateTime;

import jakarta.validation.constraints.NotNull;

/**
 * 更新任务请求体（乐观锁）。只能改 pending 任务；version 不匹配返回 409。
 * 各字段可部分更新（null 表示不改）；status P1 只允许 pending/cancelled。
 */
public class UpdateTaskRequest {

    private String title;

    private LocalDateTime dueAt;

    private String recurrence;

    private String recurrenceRule;

    /** P1 只允许 pending / cancelled；null 表示不改 status */
    private String status;

    @NotNull
    private Integer version;

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public LocalDateTime getDueAt() {
        return dueAt;
    }

    public void setDueAt(LocalDateTime dueAt) {
        this.dueAt = dueAt;
    }

    public String getRecurrence() {
        return recurrence;
    }

    public void setRecurrence(String recurrence) {
        this.recurrence = recurrence;
    }

    public String getRecurrenceRule() {
        return recurrenceRule;
    }

    public void setRecurrenceRule(String recurrenceRule) {
        this.recurrenceRule = recurrenceRule;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }
}
