package com.aichat.Dto;

/**
 * 一条记忆变更（删除/修改）。放在 pending payload 里，确认后才真正执行。
 *
 * before 只用于回执和审计展示；执行时以 id + 当前库里内容为准，避免"用户看到的"和"改掉的"不是同一条。
 */
public class MemoryChange {

    private Long id;
    /** DELETE / UPDATE */
    private String action;
    private String before;
    private String after;

    public MemoryChange() {
    }

    public MemoryChange(Long id, String action, String before, String after) {
        this.id = id;
        this.action = action;
        this.before = before;
        this.after = after;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getBefore() {
        return before;
    }

    public void setBefore(String before) {
        this.before = before;
    }

    public String getAfter() {
        return after;
    }

    public void setAfter(String after) {
        this.after = after;
    }
}
