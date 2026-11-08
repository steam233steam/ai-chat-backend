package com.aichat.Dto;

import java.time.LocalDateTime;

/**
 * 待确认动作里的"任务候选"（M3 提取器输出 / payload 反序列化）。
 * P1 只允许 action=create（update/delete 留 P1.5，带 targetId）。
 */
public class TaskCandidate {
    /** P1 固定 "create"；P1.5 扩展 update/delete */
    private String action;
    private String title;
    private LocalDateTime dueAt;
    private String timezone;
    private String recurrence;
    private String recurrenceRule;
    /** 到点要调用的 MCP 工具短名（weather/search/now/fetch_url）；为空表示站内提醒。 */
    private String tool;
    /** MCP 工具参数，例如 {"city":"北京"}、{"q":"AI 新闻"}。 */
    private java.util.Map<String, Object> toolArgs;

    public TaskCandidate() {
    }

    public TaskCandidate(String action, String title, LocalDateTime dueAt,
            String timezone, String recurrence, String recurrenceRule) {
        this.action = action;
        this.title = title;
        this.dueAt = dueAt;
        this.timezone = timezone;
        this.recurrence = recurrence;
        this.recurrenceRule = recurrenceRule;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

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

    public String getTimezone() {
        return timezone;
    }

    public void setTimezone(String timezone) {
        this.timezone = timezone;
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

    public String getTool() {
        return tool;
    }

    public void setTool(String tool) {
        this.tool = tool;
    }

    public java.util.Map<String, Object> getToolArgs() {
        return toolArgs;
    }

    public void setToolArgs(java.util.Map<String, Object> toolArgs) {
        this.toolArgs = toolArgs;
    }
}
