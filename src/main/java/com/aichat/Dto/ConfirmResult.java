package com.aichat.Dto;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * 确认动作的结果。alreadyConfirmed=true 表示是重复确认（幂等，不会重复建）。
 * memories/tasks 列出本次确认实际落库的 id（重复确认时为空列表）。
 */
public class ConfirmResult {
    private boolean alreadyConfirmed;
    private List<Map<String, Object>> memories;
    private List<Map<String, Object>> tasks;
    /**
     * 弹窗确认后实际写入聊天历史的短回执（人设化失败时是固定兜底文案）。
     * 前端直接展示这条，避免再硬编码一句“好的，都帮你设好了”。
     */
    private String receipt;
    /** 确认所属会话，仅用于 REST 确认后写短回执，不返回前端。 */
    private String sessionId;

    public ConfirmResult() {
    }

    public ConfirmResult(boolean alreadyConfirmed, List<Map<String, Object>> memories,
            List<Map<String, Object>> tasks) {
        this.alreadyConfirmed = alreadyConfirmed;
        this.memories = memories;
        this.tasks = tasks;
    }

    public boolean isAlreadyConfirmed() {
        return alreadyConfirmed;
    }

    public void setAlreadyConfirmed(boolean alreadyConfirmed) {
        this.alreadyConfirmed = alreadyConfirmed;
    }

    public List<Map<String, Object>> getMemories() {
        return memories;
    }

    public void setMemories(List<Map<String, Object>> memories) {
        this.memories = memories;
    }

    public List<Map<String, Object>> getTasks() {
        return tasks;
    }

    public void setTasks(List<Map<String, Object>> tasks) {
        this.tasks = tasks;
    }

    public String getReceipt() {
        return receipt;
    }

    public void setReceipt(String receipt) {
        this.receipt = receipt;
    }

    @JsonIgnore
    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }
}
