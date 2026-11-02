package com.aichat.Entity;

import java.time.LocalDateTime;

/**
 * 待确认动作表实体，对应 tb_pending_action。
 *
 * LLM 提取的记忆/任务候选先落这里（status=pending），用户确认后才写进 tb_user_memory /
 * tb_user_task。同一 user+session 同时只允许一个 pending（pending_slot 生成列 + 唯一键保证）。
 * action_type 由 payload 实际内容推导，只用于展示/索引，不参与确认分支。
 */
public class PendingAction {
    private Long id;
    private String userId;
    private String sessionId;
    private String actionType;
    private String payload;
    private String status;
    private LocalDateTime expiresAt;
    private Long sourceMessageId;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public PendingAction() {
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

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getActionType() {
        return actionType;
    }

    public void setActionType(String actionType) {
        this.actionType = actionType;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(LocalDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Long getSourceMessageId() {
        return sourceMessageId;
    }

    public void setSourceMessageId(Long sourceMessageId) {
        this.sourceMessageId = sourceMessageId;
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
