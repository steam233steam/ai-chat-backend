package com.aichat.Exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.aichat.Dto.Result;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // 处理业务异常
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> handleBusinessException(BusinessException e) {
        log.warn("业务异常: code={}, message={}", e.getCode(), e.getMessage());
        return build(e.getCode(), e.getMessage());
    }

    // 处理参数校验异常
    @ExceptionHandler({ IllegalArgumentException.class, MissingServletRequestParameterException.class })
    public ResponseEntity<Result<Void>> handleBadRequest(Exception e) {
        log.warn("请求参数异常: {}", e.getMessage());
        String message = (e.getMessage() == null || e.getMessage().isBlank()) ? "请求参数错误" : e.getMessage();
        return build(400, message);
    }

    // 请求体 JSON 解析失败
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<Void>> handleUnreadableBody(HttpMessageNotReadableException e) {
        log.warn("请求体解析失败: {}", e.getMessage());
        return build(400, "请求体格式错误");
    }

    // 处理所有其他异常
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleException(Exception e) {
        log.error("未处理异常", e);
        return build(500, "系统错误，请联系管理员");
    }

    private ResponseEntity<Result<Void>> build(Integer code, String message) {
        int effectiveCode = code == null ? 400 : code;
        HttpStatus status = switch (effectiveCode) {
            case 401 -> HttpStatus.UNAUTHORIZED;
            case 403 -> HttpStatus.FORBIDDEN;
            case 404 -> HttpStatus.NOT_FOUND;
            case 500 -> HttpStatus.INTERNAL_SERVER_ERROR;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Result.error(effectiveCode, message));
    }
}
