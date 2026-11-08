package com.aichat.Dto;

/**
 * 流式对话请求体。
 *
 * <p>用 POST + JSON 传 prompt：GET 带 query 会受 URL 长度限制，且问题原文会留在
 * Nginx access log 里。旧的 GET 接口保留兼容，新接入一律走这个。
 */
public class ChatStreamRequest {

    private String sessionid;

    private String question;

    private String modelName;

    public String getSessionid() {
        return sessionid;
    }

    public void setSessionid(String sessionid) {
        this.sessionid = sessionid;
    }

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }
}
