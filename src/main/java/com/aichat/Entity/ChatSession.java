package com.aichat.Entity;

import java.time.LocalDateTime;

/**
 * 聊天会话表实体，对应 tb_chat_session。
 */
public class ChatSession {
    private String userId;
    private String sessionId;
    private String title;
    private String model;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public ChatSession() {
    }

    public ChatSession(String userId, String sessionId, String title,
            LocalDateTime createTime, LocalDateTime updateTime) {
        this(userId, sessionId, title, null, createTime, updateTime);
    }

    public ChatSession(String userId, String sessionId, String title, String model,
            LocalDateTime createTime, LocalDateTime updateTime) {
        this.userId = userId;
        this.sessionId = sessionId;
        this.title = title;
        this.model = model;
        this.createTime = createTime;
        this.updateTime = updateTime;
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

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
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
