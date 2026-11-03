package com.aichat.Dto;

/**
 * 待确认动作里的"记忆候选"（M3 提取器输出 / payload 反序列化）。
 * P1 手动路径下可省略；replacesId 用于冲突替换（旧条 superseded）。
 */
public class MemoryCandidate {
    private String key;
    private String value;
    private String memType;
    private Double confidence;
    private Long replacesId;
    /** P1.1 会话隔离：global=全局画像（缺省）；session=仅当前会话生效。由 LLM 判定。 */
    private String scope;

    public MemoryCandidate() {
    }

    public MemoryCandidate(String key, String value, String memType, Double confidence, Long replacesId) {
        this.key = key;
        this.value = value;
        this.memType = memType;
        this.confidence = confidence;
        this.replacesId = replacesId;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public String getMemType() {
        return memType;
    }

    public void setMemType(String memType) {
        this.memType = memType;
    }

    public Double getConfidence() {
        return confidence;
    }

    public void setConfidence(Double confidence) {
        this.confidence = confidence;
    }

    public Long getReplacesId() {
        return replacesId;
    }

    public void setReplacesId(Long replacesId) {
        this.replacesId = replacesId;
    }

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }
}
