package com.aichat.Exception;

/**
 * 可预期的业务异常。
 * 注意：不要再声明 message 字段，父类 RuntimeException 已经保存了消息。
 */
public class BusinessException extends RuntimeException {
    private final Integer code;

    public BusinessException(String message) {
        this(400, message);
    }

    public BusinessException(Integer code, String message) {
        super(message);
        this.code = code;
    }

    public Integer getCode() {
        return code;
    }
}
