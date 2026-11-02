package com.aichat.Dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 手动创建记忆请求体。
 * source 固定 manual、confidence 固定 1.00（手动写的就是"已确认"）；
 * memType 不传默认 stable。dynamic 的 expiresAt 不传则按 dynamic-ttl-days 计算。
 */
public class CreateMemoryRequest {

    @NotBlank
    @Size(max = 32)
    private String memKey;

    @NotBlank
    @Size(max = 256)
    private String content;

    /** stable / dynamic；不传默认 stable */
    private String memType;

    /** dynamic 可显式指定过期时间；stable 必须为 null */
    private java.time.LocalDateTime expiresAt;

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

    public java.time.LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(java.time.LocalDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }
}
