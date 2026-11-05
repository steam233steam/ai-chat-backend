package com.aichat.Entity;

import java.time.LocalDateTime;

/**
 * 用户任务/计划表实体，对应 tb_user_task。
 *
 * 口语化解析后落库。due_at 可空（"记得买牛奶"无截止时间）；status 与 deleted_at 分离
 * （完成/取消是业务状态，删除是软删）；recurrence 重复任务完成时滚动到下一次。
 */
public class UserTask {
    private Long id;
    private String userId;
    private String title;
    private LocalDateTime dueAt;
    private String timezone;
    private String recurrence;
    private String recurrenceRule;
    private String status;
    private LocalDateTime deletedAt;
    private LocalDateTime lastNotifiedAt;
    private LocalDateTime completedAt;
    private String rawInput;
    private Long sourceMessageId;
    /** P1.5：任务从哪个会话发起（溯源用，不影响可见性，可见性仍全账号）。 */
    private String createdFromSession;
    private Integer version;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public UserTask() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
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

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(LocalDateTime deletedAt) {
        this.deletedAt = deletedAt;
    }

    public LocalDateTime getLastNotifiedAt() {
        return lastNotifiedAt;
    }

    public void setLastNotifiedAt(LocalDateTime lastNotifiedAt) {
        this.lastNotifiedAt = lastNotifiedAt;
    }

    public LocalDateTime getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(LocalDateTime completedAt) {
        this.completedAt = completedAt;
    }

    public String getRawInput() {
        return rawInput;
    }

    public void setRawInput(String rawInput) {
        this.rawInput = rawInput;
    }

    public Long getSourceMessageId() {
        return sourceMessageId;
    }

    public void setSourceMessageId(Long sourceMessageId) {
        this.sourceMessageId = sourceMessageId;
    }

    public String getCreatedFromSession() {
        return createdFromSession;
    }

    public void setCreatedFromSession(String createdFromSession) {
        this.createdFromSession = createdFromSession;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }
}
