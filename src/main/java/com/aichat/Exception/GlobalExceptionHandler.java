package com.aichat.Exception;

import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

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

    // 处理参数校验异常（IllegalArgumentException、缺失参数、类型不匹配、@Valid 校验失败）
    @ExceptionHandler({ IllegalArgumentException.class, MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class })
    public ResponseEntity<Result<Void>> handleBadRequest(Exception e) {
        log.warn("请求参数异常: {}", e.getMessage());
        String message = (e.getMessage() == null || e.getMessage().isBlank()) ? "请求参数错误" : e.getMessage();
        return build(400, message);
    }

    // @Valid 注解触发的方法参数校验异常
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleMethodArgumentNotValid(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(GlobalExceptionHandler::describeFieldError)
                .collect(Collectors.joining("; "));
        log.warn("参数校验失败: {}", message);
        return build(400, message.isEmpty() ? "参数校验失败" : message);
    }

    // 请求体 JSON 解析失败
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<Void>> handleUnreadableBody(HttpMessageNotReadableException e) {
        log.warn("请求体解析失败: {}", e.getMessage());
        return build(400, "请求体格式错误");
    }

    // 上传文件超过大小限制
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Result<Void>> handleMaxUploadSize(MaxUploadSizeExceededException e) {
        log.warn("上传文件超过大小限制: {}", e.getMessage());
        return build(413, "上传文件过大");
    }

    // 路径不存在（404），避免落到兜底 500
    @ExceptionHandler({ NoResourceFoundException.class, NoHandlerFoundException.class })
    public ResponseEntity<Result<Void>> handleNotFound(Exception e) {
        log.warn("资源不存在: {}", e.getMessage());
        return build(404, "接口不存在");
    }

    // 处理所有其他异常
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleException(Exception e) {
        log.error("未处理异常", e);
        return build(500, "系统错误，请联系管理员");
    }

    private static String describeFieldError(FieldError fieldError) {
        return fieldError.getField() + ": " + fieldError.getDefaultMessage();
    }

    private ResponseEntity<Result<Void>> build(Integer code, String message) {
        int effectiveCode = code == null ? 400 : code;
        HttpStatus status = switch (effectiveCode) {
            case 401 -> HttpStatus.UNAUTHORIZED;
            case 403 -> HttpStatus.FORBIDDEN;
            case 404 -> HttpStatus.NOT_FOUND;
            case 413 -> HttpStatus.PAYLOAD_TOO_LARGE;
            case 500 -> HttpStatus.INTERNAL_SERVER_ERROR;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Result.error(effectiveCode, message));
    }
}
