package com.aichat.Entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 用户长期记忆表实体，对应 tb_user_memory。
 *
 * 结构化画像（不用向量库）：memKey 是有限枚举标签（P1: style/goal/schedule/relation），
 * content 是值。status 管生命周期（active/superseded/deleted）；confidence 仅用于排序/审计，
 * 不决定生效——生效只由用户确认决定。expires_at 对 dynamic 记忆做过期，stable 为 null。
 */
public class UserMemory {
    private Long id;
    private String userId;
    private String memKey;
    private String content;
    private String memType;
    private String status;
    private BigDecimal confidence;
    private String source;
    private Long sourceMessageId;
    private Long replacesId;
    private LocalDateTime expiresAt;
    private Integer version;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public UserMemory() {
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

    public BigDecimal getConfidence() {
        return confidence;
    }

    public void setConfidence(BigDecimal confidence) {
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
}
