package com.aichat.Dto;

/**
 * Redis 中存储的单条会话消息。
 * 不依赖 Lombok，避免 IDE 未开启注解处理时出现大片报错。
 */
public class SMessage {
    private String text;      // 文本内容
    private String type;      // 消息类型：user/assistant/system
    private Long timestamp;   // 时间戳（毫秒）

    public SMessage() {
    }

    public SMessage(String text, String type, Long timestamp) {
        this.text = text;
        this.type = type;
        this.timestamp = timestamp;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Long timestamp) {
        this.timestamp = timestamp;
    }
}
