package com.aichat.Dto;

import jakarta.validation.constraints.NotNull;

/**
 * 更新记忆请求体。只能改 active 且未过期的记忆；带 version 做乐观锁。
 * content/memType 可部分更新（null 表示不改）；expiresAt 改 dynamic 续期用。
 */
public class UpdateMemoryRequest {

    private String content;

    private String memType;

    /** dynamic 记忆可续期；stable 传非 null 会被拒绝 */
    private java.time.LocalDateTime expiresAt;

    @NotNull
    private Integer version;

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

    public java.time.LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(java.time.LocalDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }
}
