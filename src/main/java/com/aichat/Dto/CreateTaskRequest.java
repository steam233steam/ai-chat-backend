package com.aichat.Dto;

import java.time.LocalDateTime;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 手动创建任务请求体（不走 LLM，兜底入口）。
 * P1 只接受 once/daily/weekly（monthly 留 P1.5）；dueAt 可空（"记得买牛奶"无时间）。
 * 时间用 LocalDateTime（P1 固定 Asia/Shanghai，前端传本地时间即可）。
 */
public class CreateTaskRequest {

    @NotBlank
    @Size(max = 128)
    private String title;

    /** 截止时间；无截止时间可为 null */
    private LocalDateTime dueAt;

    /** once/daily/weekly；不传默认 once */
    private String recurrence;

    /** weekly 用 "BYDAY=MO" 形式；daily/once 为 null */
    private String recurrenceRule;

    /** 触发句原文（截断 512），可选 */
    @Size(max = 512)
    private String rawInput;

    /** P1.5：任务从哪个会话发起（溯源用，可空）。 */
    @Size(max = 64)
    private String createdFromSession;

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

    public String getRawInput() {
        return rawInput;
    }

    public void setRawInput(String rawInput) {
        this.rawInput = rawInput;
    }

    public String getCreatedFromSession() {
        return createdFromSession;
    }

    public void setCreatedFromSession(String createdFromSession) {
        this.createdFromSession = createdFromSession;
    }
}
