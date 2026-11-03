package com.aichat.Dto;

import java.util.List;

/**
 * 待确认动作对外视图。memories/tasks 是从 payload JSON 解析出来的结构化候选，
 * 用于前端渲染"确认卡片"；preview 是给前端/聊天直接展示的一句话。
 */
public class PendingActionVO {
    private Long id;
    private String actionType;
    private String status;
    private List<MemoryCandidate> memories;
    private List<TaskCandidate> tasks;
    private String preview;
    private java.time.LocalDateTime expiresAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getActionType() {
        return actionType;
    }

    public void setActionType(String actionType) {
        this.actionType = actionType;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public List<MemoryCandidate> getMemories() {
        return memories;
    }

    public void setMemories(List<MemoryCandidate> memories) {
        this.memories = memories;
    }

    public List<TaskCandidate> getTasks() {
        return tasks;
    }

    public void setTasks(List<TaskCandidate> tasks) {
        this.tasks = tasks;
    }

    public String getPreview() {
        return preview;
    }

    public void setPreview(String preview) {
        this.preview = preview;
    }

    public java.time.LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(java.time.LocalDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }
}
