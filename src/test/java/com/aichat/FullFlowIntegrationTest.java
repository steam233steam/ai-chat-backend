package com.aichat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
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
import com.aichat.Dto.UserDto;
import com.aichat.Repository.UserRepository;
import static com.aichat.utils.RedisConstants.*;

/**
 * 一体化集成测试（端到端全链路）
 * ============================================================================
 *
 *  这个测试类是本项目最重要的"验收测试"：
 *  它不 Mock 任何东西，而是【真实启动 Spring Boot + 连真实的 MySQL(13306)
 *  + Redis(16379) + Ollama(11434)】，通过 HTTP 请求跑一遍完整的用户旅程，
 *  验证整个后端在真实环境里能工作。
 *
 *  对照你已有的 36 个单元测试：
 *  - 单元测试：Mock 掉 Repository/Redis/DB，只证明"每个零件能转"
 *  - 集成测试（本类）：不 Mock，连真实服务，证明"整辆车能开"
 *
 *  覆盖的完整流程（按 @Order 顺序执行）：
 *  1. 发验证码            （60s 冷却 + 60s 内再发被拒）
 *  2. 验证码登录          （新用户自动创建 + 新用户标记 + 返回 token）
 *  3. 获取当前用户        （isNewUser=true）
 *  4. 检查新用户          （/is-new-user）
 *  5. 设置密码            （取消新用户标记）
 *  6. 修改昵称            （合法成功 + 超长被拒 400）
 *  7. 获取模型列表        （公开接口，无需登录）
 *  8. 新建会话发消息       （会话锁模型 + 落库 + 缓存，走本地 Ollama）
 *  9. 切模型触发 409      （会话锁模型冲突，不会真的调 DeepSeek）
 *  10. 查历史             （验证消息确实落库了，带 model 字段）
 *  11. 查会话列表          （带锁定模型字段）
 *  12. 清会话             （清完历史为空）
 *  13. 登出               （token 失效，后续请求 401）
 *
 *  运行方式：
 *    mvn test -Dtest=FullFlowIntegrationTest
 *
 *  前置条件：
 *    - 虚拟机已启动（MySQL 13306、Redis 16379 可连）
 *    - Ollama 已启动且加载了 qwen2.5-coder:7b
 *    - 不需要 DeepSeek API Key（本测试只走本地 Ollama，省 token 费用）
 *
 *  数据隔离策略（重要）：
 *    - 整个测试类【共用一个手机号】，登录一次拿到的 token 给 Order 2~13 全程用
 *    - 数据只在 @BeforeAll（整套测试开始前）清一次，清的是【上一次测试运行】的残留
 *    - 为什么不清每个方法前：因为 token 是跨方法共享的 state，清了数据 token 就失效了
 *
 * ============================================================================
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FullFlowIntegrationTest {

    @LocalServerPort
    private int port;

    /** 测试用手机号（整个类共用，@BeforeAll 里生成） */
    private static String testPhone;
    /** 登录拿到的 token（@BeforeAll 后续由 Order 2 填充） */
    private static String token;
    /** 会话 id（@BeforeAll 里生成，@BeforeAll 后续发消息用） */
    private static String sessionA;

    /** 测试用的 RestTemplate：HttpClient5 工厂 + 不抛 4xx 的 ErrorHandler */
    private static RestTemplate rest;

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /** 是否已清理过数据（保证整个类只清一次） */
    private static boolean dataCleaned = false;

    /**
     * 整个测试类只初始化一次（生成手机号/会话 id、初始化 rest）。
     * 这里只做"不依赖 Spring bean"的初始化；
     * 清数据库/Redis 的逻辑放 @BeforeEach（那时 bean 已注入）。
     */
    @BeforeAll
    static void initOnce() {
        testPhone = "139" + String.format("%08d", System.currentTimeMillis() % 10_000_000L);
        sessionA = "itest_s_" + System.currentTimeMillis();
        setupRest();  // 初始化 rest（不依赖 Spring）
    }

    /**
     * 每个测试方法前清一次数据（用 dataCleaned 保证整个类只真正清一次）。
     *
     * 为什么在 @BeforeEach（而不是 @BeforeAll）里清：
     * @BeforeAll 是 static，拿不到 @Autowired 的 jdbc/stringRedisTemplate；
     * @BeforeEach 能拿到，且只在第一个方法前真正清，后面靠 dataCleaned 标志跳过。
     *
     * 清理"上一次测试运行"残留的：
     * - 数据库：该手机号的用户/会话/消息
     * - Redis：冷却 key（60s 防刷，不清会挡本次）、验证码 key（残留干扰）
     */
    @BeforeEach
    void cleanTestDataOnce() {
        if (dataCleaned) {
            return;
        }
        cleanUpJdbc();
        cleanUpRedis();
        dataCleaned = true;
    }

    /** 清数据库：删本测试手机号的所有数据 */
    private void cleanUpJdbc() {
        jdbc.update("DELETE FROM tb_chat_message WHERE user_id = (SELECT uid FROM tb_user WHERE phone = ?)", testPhone);
        jdbc.update("DELETE FROM tb_chat_session WHERE user_id = (SELECT uid FROM tb_user WHERE phone = ?)", testPhone);
        jdbc.update("DELETE FROM tb_user WHERE phone = ?", testPhone);
    }

    /** 清 Redis：删冷却 key + 验证码 key（上一次运行残留） */
    private void cleanUpRedis() {
        stringRedisTemplate.delete(LOGIN_CODE_COOLDOWN_KEY_PREFIX + testPhone);
        stringRedisTemplate.delete(LOGIN_CODE_KEY_PREFIX + testPhone);
    }

    /**
     * 初始化 RestTemplate。
     *
     * 关键设计：
     * 1. 用 HttpComponentsClientHttpRequestFactory（Apache HttpClient5），
     *    不用 JDK 默认的 SimpleClientHttpRequestFactory（后者对 4xx 会抛 HttpRetryException）
     * 2. 自定义 ResponseErrorHandler，让【所有状态码都正常返回】，不抛异常。
     *    这样测试代码才能拿到 400/401/409 的 body 来断言业务 code，
     *    而不是被 RestTemplate 默认行为拦截抛异常。这是"测错误响应"的标准做法。
     */
    private static void setupRest() {
        RestTemplate template = new RestTemplate(new HttpComponentsClientHttpRequestFactory());
        template.setErrorHandler(new ResponseErrorHandler() {
            @Override
            public boolean hasError(ClientHttpResponse response) {
                return false;  // 永远不抛，让 4xx/5xx 也正常返回 body
            }
            @Override
            public void handleError(ClientHttpResponse response) {
                // 不会被调用（hasError 恒 false）
            }
        });
        rest = template;
    }

    /** 把相对路径转成绝对 URL（http://localhost:port/path） */
    private String abs(String relativePath) {
        return "http://localhost:" + port + relativePath;
    }

    // =====================================================================
    //  1. 发验证码
    // =====================================================================
    @Test
    @Order(1)
    @DisplayName("发验证码：冷却期内可发，立即再发被拒")
    void sendVerificationCode() {
        // 第一次发：应该成功（code=200）
        ResponseEntity<Result<String>> first = postForm("/user/code?phone=" + testPhone, null, RESULT_STRING);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getBody().getCode()).isEqualTo(200);
        assertThat(first.getBody().getData()).isEqualTo("验证码已发送");

        // 60 秒冷却期内再发：HTTP 400 + 业务 code 400（GlobalExceptionHandler 把 400 映射成 HTTP 400）
        ResponseEntity<Result<String>> second = postForm("/user/code?phone=" + testPhone, null, RESULT_STRING);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(second.getBody().getCode()).isEqualTo(400);
        assertThat(second.getBody().getMessage()).contains("频繁");
    }

    // =====================================================================
    //  2. 验证码登录（新用户）
    // =====================================================================
    @Test
    @Order(2)
    @DisplayName("验证码登录：新用户自动创建 + 返回 token")
    void loginByCode() {
        // 绕过 sendCode 冷却限制：直接往 Redis 写一个已知验证码
        userRepository.saveCode(testPhone, "123456");

        LoginFormDTO form = new LoginFormDTO();
        form.setPhone(testPhone);
        form.setCode("123456");

        ResponseEntity<Result<String>> resp = postJson("/user/login", form, RESULT_STRING);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().getCode()).isEqualTo(200);
        assertThat(resp.getBody().getData()).isNotBlank();  // 返回了 token

        token = resp.getBody().getData();  // 存到静态字段，供 Order 3~13 用
    }

    // =====================================================================
    //  3. 获取当前用户信息（含 isNewUser）
    // =====================================================================
    @Test
    @Order(3)
    @DisplayName("获取当前用户：新用户 isNewUser=true")
    void meReturnsNewUser() {
        ResponseEntity<Result<UserDto>> resp = getWithAuth("/user/me", RESULT_USER);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().getCode()).isEqualTo(200);
        assertThat(resp.getBody().getData()).isNotNull();
        assertThat(resp.getBody().getData().getPhone()).isEqualTo(testPhone);
        // 新用户（还没设密码）
        assertThat(resp.getBody().getData().getIsNewUser()).isTrue();
    }

    // =====================================================================
    //  4. 检查是否新用户
    // =====================================================================
    @Test
    @Order(4)
    @DisplayName("检查新用户：/is-new-user 返回 true")
    void isNewUserReturnsTrue() {
        ResponseEntity<Result<Boolean>> resp = getWithAuth("/user/is-new-user", RESULT_BOOLEAN);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().getCode()).isEqualTo(200);
        assertThat(resp.getBody().getData()).isTrue();
    }

    // =====================================================================
    //  5. 设置密码（取消新用户标记）
    // =====================================================================
    @Test
    @Order(5)
    @DisplayName("设置密码：之后 isNewUser=false")
    void setPassword() {
        // set-password 需要验证码
        userRepository.saveCode(testPhone, "654321");
        LoginFormDTO form = new LoginFormDTO();
        form.setPhone(testPhone);
        form.setCode("654321");
        form.setPassword("test_pass_123");

        HttpHeaders headers = authHeaders();
        ResponseEntity<Result<String>> resp =
                rest.exchange(abs("/user/set-password"), HttpMethod.PUT, new HttpEntity<>(form, headers), RESULT_STRING);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().getCode()).isEqualTo(200);

        // 设完密码后，isNewUser 应该变 false
        ResponseEntity<Result<Boolean>> check = getWithAuth("/user/is-new-user", RESULT_BOOLEAN);
        assertThat(check.getBody().getData()).isFalse();
    }

    // =====================================================================
    //  6. 修改昵称
    // =====================================================================
    @Test
    @Order(6)
    @DisplayName("修改昵称：合法昵称成功，超长被拒")
    void updateProfile() {
        UserDto body = new UserDto();
        body.setName("测试用户_改名");

        HttpHeaders headers = authHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<Result<String>> resp =
                rest.exchange(abs("/user/profile"), HttpMethod.PUT, new HttpEntity<>(body, headers), RESULT_STRING);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().getCode()).isEqualTo(200);

        // 超长昵称（>20 字符）应被拒 400（GlobalExceptionHandler 把 400 映射成 HTTP 400）
        UserDto tooLong = new UserDto();
        tooLong.setName("这是一个超过二十个字符的昵称长度肯定会超过限制的长度");
        ResponseEntity<Result<String>> bad =
                rest.exchange(abs("/user/profile"), HttpMethod.PUT, new HttpEntity<>(tooLong, headers), RESULT_STRING);
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(bad.getBody().getCode()).isEqualTo(400);
    }

    // =====================================================================
    //  7. 获取可用模型列表（公开接口，无需登录）
    // =====================================================================
    @Test
    @Order(7)
    @DisplayName("获取模型列表：返回白名单内的模型")
    void listModels() {
        // 公开接口，不带 token 也能调
        ResponseEntity<Result<Set<String>>> resp = getPublic("/chat/models", RESULT_SET);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().getCode()).isEqualTo(200);
        assertThat(resp.getBody().getData()).contains("qwen2.5-coder:7b");
        assertThat(resp.getBody().getData()).contains("deepseek-chat");
    }

    // =====================================================================
    //  8. 新建会话发消息（同步，走本地 Ollama）
    // =====================================================================
    @Test
    @Order(8)
    @DisplayName("新建会话发消息：AI 回复 + 会话锁定模型")
    void sendMessageCreatesSession() {
        // 不传 modelName → 用默认 qwen2.5-coder:7b（本地 Ollama，不需要 API key）
        String url = "/chat/ask?sessionid=" + sessionA + "&question=" + urlEncode("你好，请介绍一下你自己");
        ResponseEntity<Result<String>> resp = postForm(url, null, RESULT_STRING);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().getCode()).isEqualTo(200);
        assertThat(resp.getBody().getData()).isNotBlank();  // AI 真的回了话
    }

    // =====================================================================
    //  9. 切模型触发 409（会话锁模型）
    // =====================================================================
    @Test
    @Order(9)
    @DisplayName("切模型被拒：已锁定的会话切到其他模型返回 409")
    void switchModelReturns409() {
        // sessionA 已经锁到 qwen2.5-coder:7b（Order 8 建立的）
        // 现在传 modelName=deepseek-chat，应该被 409 拒绝（且不会真的调 DeepSeek）
        String url = "/chat/ask?sessionid=" + sessionA + "&question=" + urlEncode("换个问题")
                + "&modelName=deepseek-chat";
        ResponseEntity<Result<String>> resp = postForm(url, null, RESULT_STRING);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);  // HTTP 409
        assertThat(resp.getBody().getCode()).isEqualTo(409);              // 业务层 409
        assertThat(resp.getBody().getMessage()).contains("已锁定");
    }

    // =====================================================================
    //  10. 查历史（验证消息确实落库了）
    // =====================================================================
    @Test
    @Order(10)
    @DisplayName("查历史：能取到刚发的消息（验证落库 + model 字段）")
    void getHistoryHasMessages() {
        String url = "/chat/history?sessionid=" + sessionA;
        ResponseEntity<Result<List<Map<String, Object>>>> resp = getWithAuth(url, RESULT_LIST_MAP);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().getCode()).isEqualTo(200);
        List<Map<String, Object>> messages = resp.getBody().getData();
        // 至少包含 user + assistant 各一条（Order 8 发的 + AI 回复）
        assertThat(messages.size()).isGreaterThanOrEqualTo(2);
        assertThat(messages.get(0).get("role")).isEqualTo("user");
        // 带 model 字段（验证消息级模型追踪）
        assertThat(messages.get(0).get("model")).isEqualTo("qwen2.5-coder:7b");
    }

    // =====================================================================
    //  11. 查会话列表（带锁定模型）
    // =====================================================================
    @Test
    @Order(11)
    @DisplayName("查会话列表：包含刚创建的会话 + 锁定模型")
    void getSessionList() {
        String url = "/chat/sessions";
        ResponseEntity<Result<List<Map<String, Object>>>> resp = getWithAuth(url, RESULT_LIST_MAP);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> sessions = resp.getBody().getData();
        // 找我们创建的 sessionA
        Map<String, Object> found = sessions.stream()
                .filter(s -> sessionA.equals(s.get("sessionId")))
                .findFirst().orElse(null);
        assertThat(found).isNotNull();
        assertThat(found.get("model")).isEqualTo("qwen2.5-coder:7b");
    }

    // =====================================================================
    //  12. 清会话
    // =====================================================================
    @Test
    @Order(12)
    @DisplayName("清会话：清空后历史为空")
    void clearSession() {
        String url = "/chat/clear-session?sessionid=" + sessionA;
        HttpHeaders headers = authHeaders();
        ResponseEntity<Result<String>> resp =
                rest.exchange(abs(url), HttpMethod.DELETE, new HttpEntity<>(headers), RESULT_STRING);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().getCode()).isEqualTo(200);

        // 清完后再查历史，应该是空
        ResponseEntity<Result<List<Map<String, Object>>>> after =
                getWithAuth("/chat/history?sessionid=" + sessionA, RESULT_LIST_MAP);
        assertThat(after.getBody().getData()).isEmpty();
    }

    // =====================================================================
    //  13. 登出
    // =====================================================================
    @Test
    @Order(13)
    @DisplayName("登出：token 失效，后续请求 401")
    void logoutInvalidatesToken() {
        HttpHeaders headers = authHeaders();
        ResponseEntity<Result<String>> resp =
                rest.exchange(abs("/user/logout"), HttpMethod.POST, new HttpEntity<>(headers), RESULT_STRING);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().getCode()).isEqualTo(200);

        // 登出后再用旧 token 访问 /user/me，应该 401
        ResponseEntity<Result<UserDto>> after =
                rest.exchange(abs("/user/me"), HttpMethod.GET, new HttpEntity<>(headers), RESULT_USER);
        assertThat(after.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);  // HTTP 401
        // 或者业务层也 401（取决于你的 GlobalExceptionHandler 把 401 映射成 HTTP 401 还是 200）
    }

    // =====================================================================
    //  辅助方法（复用，降低重复代码）
    //
    //  为什么用 ParameterizedTypeReference 而不是 Class：
    //  Result<T> 是泛型类，Result.class 丢失了 T 的类型信息（泛型擦除），
    //  Jackson 反序列化时不知道 data 该转成什么类型。
    //  ParameterizedTypeReference 能保留泛型，Jackson 就能正确反序列化 data 字段。
    // =====================================================================

    /** GET 公开接口（不带 token） */
    private <T> ResponseEntity<Result<T>> getPublic(String url, ParameterizedTypeReference<Result<T>> typeRef) {
        return rest.exchange(abs(url), HttpMethod.GET, new HttpEntity<>(new HttpHeaders()), typeRef);
    }

    /** GET 带 token */
    private <T> ResponseEntity<Result<T>> getWithAuth(String url, ParameterizedTypeReference<Result<T>> typeRef) {
        return rest.exchange(abs(url), HttpMethod.GET, new HttpEntity<>(authHeaders()), typeRef);
    }

    /** POST 表单（@RequestParam 风格，带 token） */
    private <T> ResponseEntity<Result<T>> postForm(String url, Object body, ParameterizedTypeReference<Result<T>> typeRef) {
        return rest.exchange(abs(url), HttpMethod.POST, new HttpEntity<>(body, authHeaders()), typeRef);
    }

    /** POST JSON（@RequestBody 风格，带 token） */
    private <T> ResponseEntity<Result<T>> postJson(String url, Object body, ParameterizedTypeReference<Result<T>> typeRef) {
        HttpHeaders headers = authHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(abs(url), HttpMethod.POST, new HttpEntity<>(body, headers), typeRef);
    }

    /** 构造带 Authorization 的请求头 */
    private HttpHeaders authHeaders() {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.set("Authorization", token);
        }
        return headers;
    }

    /** URL 编码中文（避免 query 参数里中文乱码） */
    private String urlEncode(String s) {
        try {
            return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** 快捷方法：Result<String> 的 TypeReference（大部分接口 data 是 String） */
    private static final ParameterizedTypeReference<Result<String>> RESULT_STRING =
            new ParameterizedTypeReference<>() {};

    /** 快捷方法：Result<Boolean> */
    private static final ParameterizedTypeReference<Result<Boolean>> RESULT_BOOLEAN =
            new ParameterizedTypeReference<>() {};

    /** 快捷方法：Result<UserDto> */
    private static final ParameterizedTypeReference<Result<UserDto>> RESULT_USER =
            new ParameterizedTypeReference<>() {};

    /** 快捷方法：Result<List<Map<String,Object>>> */
    private static final ParameterizedTypeReference<Result<List<Map<String, Object>>>> RESULT_LIST_MAP =
            new ParameterizedTypeReference<>() {};

    /** 快捷方法：Result<Set<String>> */
    private static final ParameterizedTypeReference<Result<java.util.Set<String>>> RESULT_SET =
            new ParameterizedTypeReference<>() {};
}
