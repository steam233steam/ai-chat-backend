package com.aichat.Exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import com.aichat.Dto.Result;

/**
 * 业务异常码 → HTTP 状态码的映射回归测试。
 * 会话锁模型冲突用 409，前端靠 HTTP 状态码区分"参数错误"和"模型冲突"。
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void shouldMapConflictCodeToHttp409() {
        ResponseEntity<Result<Void>> response =
                handler.handleBusinessException(new BusinessException(409, "该会话已锁定为 qwen2.5-coder:7b"));

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(409);
    }

    @Test
    void shouldKeepBadRequestMappingForUnknownCodes() {
        ResponseEntity<Result<Void>> response =
                handler.handleBusinessException(new BusinessException(400, "参数错误"));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }
}
