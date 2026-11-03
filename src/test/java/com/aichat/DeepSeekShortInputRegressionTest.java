package com.aichat;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

import com.aichat.Dto.LoginFormDTO;
import com.aichat.Dto.Result;
import com.aichat.Repository.UserRepository;
import static com.aichat.utils.RedisConstants.*;

/**
 * 针对 DeepSeek 路由短输入场景的定向回归测试。
 *
 * 背景：<ds_safety> 标签在"你是"这种超短输入下稳定复现。本测试直接走真实 DeepSeek
 * 路由（OpenAI 兼容协议），用最贴近现场的最小输入集（"你是"），断言返回体里
 * 不再夹带 ds_safety 标签。
 *
 * 依赖：application-local.properties 里配了真实 DEEPSEEK_API_KEY（本地）。
 * 无 key 时（CI/干净环境）用 Assumptions 跳过，不阻塞全绿。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DeepSeekShortInputRegressionTest {

    @LocalServerPort
    private int port;

    private static RestTemplate rest;
    private static String phone;
    private static String token;

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private org.springframework.core.env.Environment environment;

    private static final ParameterizedTypeReference<Result<String>> RESULT_STRING =
            new ParameterizedTypeReference<>() {};

    @BeforeAll
    static void setupRest() {
        RestTemplate template = new RestTemplate(new HttpComponentsClientHttpRequestFactory());
        template.setErrorHandler(new ResponseErrorHandler() {
            @Override
            public boolean hasError(ClientHttpResponse response) {
                return false;
            }

            @Override
            public void handleError(ClientHttpResponse response) {
            }
        });
        rest = template;
    }

    private String abs(String path) {
        return "http://localhost:" + port + path;
    }

    private HttpHeaders authHeaders() {
        HttpHeaders h = new HttpHeaders();
        if (token != null) {
            h.set("Authorization", token);
        }
        return h;
    }

    private String urlEncode(String s) {
        try {
            return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** 确保有可用 token：注入已知验证码登录（绕过冷却）。清掉冷却 key 再发。 */
    private void ensureToken() {
        if (token != null) {
            return;
        }
        stringRedisTemplate.delete(LOGIN_CODE_COOLDOWN_KEY_PREFIX + phone);
        userRepository.saveCode(phone, "000000");
        LoginFormDTO form = new LoginFormDTO();
        form.setPhone(phone);
        form.setCode("000000");
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Result<String>> login =
                rest.exchange(abs("/user/login"), HttpMethod.POST, new HttpEntity<>(form, h), RESULT_STRING);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        token = login.getBody().getData();
    }

    @Test
    void shortInputNoSafetyTagLeak() {
        String apiKey = environment.getProperty("spring.ai.openai.api-key", "sk-deepseek-placeholder");
        boolean hasRealKey = apiKey != null && !apiKey.isBlank() && !apiKey.startsWith("sk-deepseek-placeholder");
        org.junit.jupiter.api.Assumptions.assumeTrue(hasRealKey, "无真实 DeepSeek key，跳过");
        DbTestSupport.assumeMysqlAvailable(jdbc);
        DbTestSupport.assumeRedisAvailable(stringRedisTemplate);

        phone = "135" + String.format("%08d", System.currentTimeMillis() % 100_000_000L);
        jdbc.update("DELETE FROM tb_chat_message WHERE user_id = (SELECT uid FROM tb_user WHERE phone = ?)", phone);
        jdbc.update("DELETE FROM tb_chat_session WHERE user_id = (SELECT uid FROM tb_user WHERE phone = ?)", phone);
        jdbc.update("DELETE FROM tb_user WHERE phone = ?", phone);
        stringRedisTemplate.delete(LOGIN_CODE_KEY_PREFIX + phone);

        ensureToken();
        String sid = "reg_ds_" + System.currentTimeMillis();
        String url = "/chat/ask?sessionid=" + sid
                + "&question=" + urlEncode("你是")
                + "&modelName=" + urlEncode("deepseek-chat");
        ResponseEntity<Result<String>> resp =
                rest.exchange(abs(url), HttpMethod.POST, new HttpEntity<>(authHeaders()), RESULT_STRING);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        String content = resp.getBody().getData();
        assertThat(content).isNotBlank();
        // 核心断言：返回体里绝不能再出现 <ds_safety> 标签（净化 + 简化 prompt 双重保证）
        assertThat(content.toLowerCase()).doesNotContain("ds_safety");
        System.out.println("[DeepSeek 短输入回归] 模型原始返回: " + content);
    }
}
