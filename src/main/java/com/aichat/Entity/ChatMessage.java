package com.aichat.Entity;

import java.time.LocalDateTime;

/**
 * 聊天消息表实体，对应 tb_chat_message。
 */
public class ChatMessage {
    private Long id;
    private String userId;
    private String sessionId;
    private String role;
    private String content;
    private String model;
    private LocalDateTime createTime;

    public ChatMessage() {
    }

    public ChatMessage(String userId, String sessionId, String role, String content, LocalDateTime createTime) {
        this(userId, sessionId, role, content, null, createTime);
    }

    public ChatMessage(String userId, String sessionId, String role, String content, String model,
            LocalDateTime createTime) {
        this.userId = userId;
        this.sessionId = sessionId;
        this.role = role;
        this.content = content;
        this.model = model;
        this.createTime = createTime;
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

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
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
}
