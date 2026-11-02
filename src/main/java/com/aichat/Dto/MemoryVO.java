package com.aichat.Dto;

import java.time.LocalDateTime;

/**
 * 记忆对外视图（返回给前端）。不含 userId（按当前登录用户隔离，无需回显）。
 * expired 是由 expires_at 计算出的瞬态字段，不落库。
 */
public class MemoryVO {
    private Long id;
    private String memKey;
    private String content;
    private String memType;
    private String status;
    private String confidence;
    private String source;
    private Long sourceMessageId;
    private Long replacesId;
    private LocalDateTime expiresAt;
    private Integer version;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    /** 计算字段：expires_at 非空且 <= now */
    private Boolean expired;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getMemKey() {
        return memKey;
    }

    public void setMemKey(String memKey) {
        this.memKey = memKey;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getMemType() {
        return memType;
    }

    public void setMemType(String memType) {
        this.memType = memType;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getConfidence() {
        return confidence;
    }

    public void setConfidence(String confidence) {
        this.confidence = confidence;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public Long getSourceMessageId() {
        return sourceMessageId;
    }

    public void setSourceMessageId(Long sourceMessageId) {
        this.sourceMessageId = sourceMessageId;
    }

    public Long getReplacesId() {
        return replacesId;
    }

    public void setReplacesId(Long replacesId) {
        this.replacesId = replacesId;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(LocalDateTime expiresAt) {
        this.expiresAt = expiresAt;
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

    public Boolean getExpired() {
        return expired;
    }

    public void setExpired(Boolean expired) {
        this.expired = expired;
    }
}
