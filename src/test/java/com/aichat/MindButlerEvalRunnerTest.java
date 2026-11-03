package com.aichat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

import com.aichat.Dto.MemoryCandidate;
import com.aichat.Dto.PendingActionVO;
import com.aichat.Dto.TaskCandidate;
import com.aichat.Entity.ChatMessage;
import com.aichat.Mapper.ChatMessageMapper;
import com.aichat.Service.IntentOutcome;
import com.aichat.Service.IntentService;
import com.aichat.utils.IntentDetector;
import com.aichat.utils.IntentJsonParser;
import com.aichat.utils.IntentJsonParser.Parsed;
import com.aichat.utils.UserHolder;

/**
 * MindButler P1 意图提取评测集 runner（M4）。
 *
 * 用 docs/eval/mindbutler-intent-eval.json（40 条口语）驱动 IntentService 全链路，
 * 统计提取准确率，作为 P1 的验收指标（"口语化 → 结构化候选" 的正确性）。
 *
 * 两种跑法（同一流水线，区别只在 IntentExtractor 是否真调 LLM）：
 * - 【桩模式】（默认）：@MockBean 掉 IntentExtractor，把 expected JSON 直接喂进去，
 *   验证的是"候选 → 校验 → pending → 确认落库"这条下游链路（确定、不花 token、可进 CI）。
 * - 【真模型模式】：设置 app.mindbutler.eval.realModel=true，IntentExtractor 走真实
 *   deepseek-chat，验证真实 LLM 的提取准确率（需要配真实 key，会花 token，CI 可跳过）。
 *
 * 真模型模式靠 application-local.properties 配 key；没配 key 时自动降级成桩模式。
 */
@SpringBootTest(properties = "spring.data.redis.repositories.enabled=false")
class MindButlerEvalRunnerTest {

    private static final String EVAL_RESOURCE = "eval/mindbutler-intent-eval.json";

    @MockBean
    private com.aichat.Service.IntentExtractor intentExtractor;

    @Autowired
    private IntentService intentService;
    @Autowired
    private com.aichat.Service.PendingActionService pendingActionService;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ChatMessageMapper chatMessageMapper;
    @Autowired
    private Environment environment;

    /** 评测用户（隔离，不进 tb_user；MindButler 三层按 user_id 逻辑隔离） */
    private String userId;

    private final List<EvalCase> cases = new ArrayList<>();

    /** 真模型开关：app.mindbutler.eval.real-model=true 时跑真实 LLM，否则跑桩模式 */
    private boolean realModel;

    @BeforeEach
    void setUp() {
        DbTestSupport.assumeMysqlAvailable(jdbc);
        userId = "MBEVAL_" + System.nanoTime();
        UserDtoHolder.set(userId);
        realModel = "true".equalsIgnoreCase(
                environment.getProperty("app.mindbutler.eval.real-model", "false"));
        // 没配真实 key 时强制桩模式，避免真模型模式 500 污染评测
        String key = environment.getProperty("spring.ai.openai.api-key", "sk-deepseek-placeholder");
        if (realModel && (key == null || key.isBlank() || key.startsWith("sk-deepseek-placeholder"))) {
            realModel = false;
        }
        loadCases();
        cleanup();
    }

    private void cleanup() {
        pendingActionServiceRejectAllLeftovers();
        jdbc.update("DELETE FROM tb_user_task WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM tb_user_memory WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM tb_pending_action WHERE user_id = ?", userId);
        UserHolder.removeUser();
        UserDtoHolder.set(userId);
    }

    /** 评测跑完后的 pending 兜底清理（确认/拒绝/过期都清掉） */
    private void pendingActionServiceRejectAllLeftovers() {
        List<Long> ids = jdbc.query(
                "SELECT id FROM tb_pending_action WHERE user_id = ?",
                (rs, i) -> rs.getLong("id"), userId);
        ids.forEach(id -> {
            try {
                pendingActionService.reject(id);
            } catch (Exception ignore) {
                // 已确认/过期无所谓，忽略
            }
        });
        jdbc.update("DELETE FROM tb_pending_action WHERE user_id = ?", userId);
    }

    @Test
    void shouldEvaluateAllCasesAndReportAccuracy() {
        int total = cases.size();
        int hit = 0;
        List<String> misses = new ArrayList<>();
        List<String> report = new ArrayList<>();

        for (EvalCase c : cases) {
            // 每条用例给独立 session（pending 隔离，互不干扰）
            String session = "eval_" + c.id;
            Long sourceMessageId = insertMessage(c.input, session);

            // 桩模式：把期望 JSON 喂给提取器；真模型：不 stub，让真实 LLM 出候选
            if (!realModel) {
                when(intentExtractor.extract(any(IntentDetector.Intent.class), anyString()))
                        .thenReturn(buildParsed(c));
            }

            IntentOutcome outcome = intentService.handle(session, c.input, sourceMessageId);

            boolean ok = assertCase(c, outcome);
            if (ok) {
                hit++;
            } else {
                misses.add("case#" + c.id + " (" + c.input + ")");
            }
            report.add(String.format("[%s] #%d %s → %s",
                    ok ? "OK" : "MISS", c.id, c.type,
                    outcome.extracted ? "提取出候选(写pending)"
                            : outcome.shouldConfirm ? "确认"
                            : outcome.shouldReject ? "拒绝"
                            : "走正常聊天/兜底"));
        }

        double accuracy = total == 0 ? 0 : (double) hit / total;
        System.out.println("\n================ MindButler P1 意图提取评测 ================");
        report.forEach(System.out::println);
        System.out.println("---------------------------------------------------------------");
        System.out.printf("模式: %s | 总用例: %d | 命中: %d | %s: %.1f%%%n",
                realModel ? "真模型(deepseek-chat)" : "桩(expected JSON，测下游链路)",
                total, hit, realModel ? "真实提取准确率" : "链路准确率（不代表 LLM 准确率）",
                accuracy * 100);
        if (!misses.isEmpty()) {
            System.out.println("未命中: " + String.join(", ", misses));
        }

        // P1 验收门槛：桩模式应 100%（链路确定性）；真模型模式给个宽松下限
        if (realModel) {
            assertThat(accuracy).isGreaterThanOrEqualTo(0.75);
        } else {
            assertThat(misses).isEmpty();
        }
    }

    // ---------- 逐条断言 ----------

    private boolean assertCase(EvalCase c, IntentOutcome outcome) {
        switch (c.type) {
            case "memory", "task", "mixed" -> {
                // 应提取出候选并写 pending
                if (!outcome.extracted) {
                    return false;
                }
                // pending 里应有对应候选，确认后才落库
                PendingActionVO pending = pendingActionService.current("eval_" + c.id);
                assertThat(pending).isNotNull();
                verifyCandidates(c, pending);
                // 真模型模式额外确认落库（桩模式 pending 存在即视为链路通）
                if (realModel) {
                    pendingActionService.confirm(pending.getId());
                }
                return true;
            }
            case "none" -> {
                // 普通聊天：不应写 pending（shouldExtract=true 走正常聊天）
                return outcome.shouldExtract;
            }
            case "reject_sensitive" -> {
                // 敏感信息：提取链路存在，但候选应被过滤（无 pending 落库）
                return !outcome.extracted;
            }
            case "confirm" -> {
                // 确认词但无 pending → 走正常聊天（不当成确认）
                return outcome.shouldExtract;
            }
            case "reject" -> {
                // 拒绝词但无 pending → 走正常聊天
                return outcome.shouldExtract;
            }
            default -> {
                return false;
            }
        }
    }

    /** 校验 pending payload 里的候选符合期望（标题/重复规则/记忆 key 命中）。 */
    private void verifyCandidates(EvalCase c, PendingActionVO pending) {
        switch (c.type) {
            case "memory" -> {
                assertThat(pending.getMemories()).isNotEmpty();
                MemoryCandidate m = pending.getMemories().get(0);
                if (c.expectKey != null) {
                    assertThat(m.getKey()).isEqualTo(c.expectKey);
                }
                if (c.expectValueContains != null) {
                    assertThat(m.getValue()).contains(c.expectValueContains);
                }
            }
            case "task" -> {
                assertThat(pending.getTasks()).isNotEmpty();
                TaskCandidate t = pending.getTasks().get(0);
                if (c.expectTitleContains != null) {
                    assertThat(t.getTitle()).contains(c.expectTitleContains);
                }
                if (c.expectRecurrence != null) {
                    assertThat(t.getRecurrence()).isEqualTo(c.expectRecurrence);
                }
                if (c.expectRule != null) {
                    assertThat(t.getRecurrenceRule()).isEqualTo(c.expectRule);
                }
            }
            case "mixed" -> {
                if (c.expectMemoryKey != null) {
                    assertThat(pending.getMemories()).isNotEmpty();
                    assertThat(pending.getMemories().get(0).getKey()).isEqualTo(c.expectMemoryKey);
                }
                if (c.expectTaskTitleContains != null) {
                    assertThat(pending.getTasks()).isNotEmpty();
                    assertThat(pending.getTasks().get(0).getTitle()).contains(c.expectTaskTitleContains);
                }
            }
            default -> {
                // none / reject / confirm 无候选断言
            }
        }
    }

    /** 把期望 JSON 构造成 Parsed（桩模式给提取器用）。 */
    private Parsed buildParsed(EvalCase c) {
        List<MemoryCandidate> memories = new ArrayList<>();
        List<TaskCandidate> tasks = new ArrayList<>();
        switch (c.type) {
            case "memory" -> {
                if (c.expectKey != null) {
                    memories.add(new MemoryCandidate(c.expectKey,
                            c.expectValueContains == null ? c.expectKey : c.expectValueContains,
                            c.expectMemType, 0.9, null));
                }
            }
            case "task" -> {
                tasks.add(new TaskCandidate("create",
                        c.expectTitleContains == null ? "任务" : c.expectTitleContains,
                        c.expectRecurrence == null || "once".equals(c.expectRecurrence)
                                ? null : LocalDateTime.now().plusDays(1),
                        "Asia/Shanghai", c.expectRecurrence, c.expectRule));
            }
            case "mixed" -> {
                if (c.expectMemoryKey != null) {
                    memories.add(new MemoryCandidate(c.expectMemoryKey, "画像值", "stable", 0.9, null));
                }
                if (c.expectTaskTitleContains != null) {
                    // daily/weekly 按契约必须有 dueAt；桩数据也要满足，否则会被生产校验正确丢弃
                    LocalDateTime due = c.expectTaskRecurrence == null || "once".equals(c.expectTaskRecurrence)
                            ? null : LocalDateTime.now().plusDays(1);
                    tasks.add(new TaskCandidate("create", c.expectTaskTitleContains, due,
                            "Asia/Shanghai", c.expectTaskRecurrence, c.expectTaskRule));
                }
            }
            default -> {
                // none / reject / confirm / reject_sensitive：候选留空
            }
        }
        return new Parsed(memories, tasks);
    }

    private Long insertMessage(String content, String session) {
        ChatMessage m = new ChatMessage(userId, session, "user", content, null, LocalDateTime.now());
        chatMessageMapper.insert(m);
        return m.getId();
    }

    // ---------- 评测集加载 ----------

    private void loadCases() {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(EVAL_RESOURCE)) {
            assertThat(is).as("评测集资源 " + EVAL_RESOURCE + " 应存在").isNotNull();
            com.fasterxml.jackson.databind.ObjectMapper om =
                    new com.fasterxml.jackson.databind.ObjectMapper()
                            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
            com.fasterxml.jackson.databind.JsonNode root =
                    om.readValue(is, com.fasterxml.jackson.databind.JsonNode.class);
            for (com.fasterxml.jackson.databind.JsonNode node : root.get("cases")) {
                EvalCase c = new EvalCase();
                c.id = node.get("id").asInt();
                c.input = node.get("input").asText();
                c.type = node.get("type").asText();
                c.expectKey = text(node, "expectKey");
                c.expectValueContains = text(node, "expectValueContains");
                c.expectMemType = text(node, "expectMemType");
                c.expectTitleContains = text(node, "expectTitleContains");
                c.expectRecurrence = text(node, "expectRecurrence");
                c.expectRule = text(node, "expectRule");
                c.expectMemoryKey = text(node, "expectMemoryKey");
                c.expectTaskTitleContains = text(node, "expectTaskTitleContains");
                c.expectTaskRecurrence = text(node, "expectTaskRecurrence");
                c.expectTaskRule = text(node, "expectTaskRule");
                cases.add(c);
            }
        } catch (Exception e) {
            throw new IllegalStateException("加载评测集失败: " + EVAL_RESOURCE, e);
        }
        assertThat(cases).hasSize(40);
    }

    private static String text(com.fasterxml.jackson.databind.JsonNode node, String field) {
        com.fasterxml.jackson.databind.JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? null : v.asText();
    }

    /** 单条评测用例（字段全可选，按 type 决定用哪些）。 */
    private static class EvalCase {
        int id;
        String input;
        String type;
        String expectKey;
        String expectValueContains;
        String expectMemType;
        String expectTitleContains;
        String expectRecurrence;
        String expectRule;
        String expectMemoryKey;
        String expectTaskTitleContains;
        String expectTaskRecurrence;
        String expectTaskRule;
    }

    /** UserHolder 的小封装（避免测试里裸用 static，便于清理）。 */
    private static final class UserDtoHolder {
        static void set(String userId) {
            com.aichat.Dto.UserDto u = new com.aichat.Dto.UserDto();
            u.setId(userId);
            u.setPhone("13800000000");
            UserHolder.saveUser(u);
        }
    }
}
