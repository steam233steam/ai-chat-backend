package com.aichat.Service.intent;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

import com.aichat.Dto.IntentExtractionResult;
import com.aichat.Dto.MemoryCandidate;
import com.aichat.Dto.TaskCandidate;
import com.aichat.Service.IntentExtractor;
import com.aichat.utils.IntentDetector;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * S0 影子评测：同一批 40 条口语，分别喂给旧引擎（提示词 + 固定 JSON）和新引擎（工具调用），
 * 比较「候选抽取」这一步的准确率。
 *
 * <p>只在 {@code app.mindbutler.eval.real-model=true} 且配了真实 key 时跑，否则整类跳过——
 * 它要真调模型，花 token、耗时几十秒，不适合每次都跑。
 *
 * <p>判分只看抽取结果本身（key/标题/重复规则/条数），不看下游 pending 与落库，
 * 因为两条路的下游完全一样，差异只可能出在抽取。
 */
@SpringBootTest(properties = "spring.data.redis.repositories.enabled=false")
class IntentToolEngineEvalTest {

    private static final String EVAL_RESOURCE = "eval/mindbutler-intent-eval.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private IntentToolEngine toolEngine;
    @Autowired
    private IntentExtractor promptExtractor;
    @Autowired
    private Environment environment;

    private boolean realModel;

    @BeforeEach
    void setUp() {
        realModel = "true".equalsIgnoreCase(
                environment.getProperty("app.mindbutler.eval.real-model", "false"));
        String key = environment.getProperty("spring.ai.openai.api-key", "");
        if (key.isBlank() || key.startsWith("sk-deepseek-placeholder")) {
            realModel = false;
        }
    }

    @Test
    void shouldCompareToolEngineWithPromptEngine() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(realModel,
                "需要真模型：-Dapp.mindbutler.eval.real-model=true 且配 spring.ai.openai.api-key");

        List<JsonNode> cases = loadCases();
        int oldHit = 0;
        int newHit = 0;
        List<String> rows = new ArrayList<>();

        for (JsonNode c : cases) {
            int id = c.path("id").asInt();
            String input = c.path("input").asText();
            IntentDetector.Intent intent = IntentDetector.detect(input, false);

            IntentExtractionResult oldResult = promptExtractor.extract(intent, input);
            IntentExtractionResult newResult = toolEngine.extract(intent, input);

            boolean oldOk = matches(c, oldResult);
            boolean newOk = matches(c, newResult);
            if (oldOk) {
                oldHit++;
            }
            if (newOk) {
                newHit++;
            }
            rows.add(String.format("#%02d %-16s 旧:%s 新:%s  %s", id, c.path("type").asText(),
                    oldOk ? "OK  " : "MISS", newOk ? "OK  " : "MISS", input));
        }

        int total = cases.size();
        double oldRate = total == 0 ? 0 : (double) oldHit / total;
        double newRate = total == 0 ? 0 : (double) newHit / total;
        System.out.println("\n========== S0 影子评测：旧引擎 vs 工具引擎 ==========");
        rows.forEach(System.out::println);
        System.out.println("-----------------------------------------------------");
        System.out.printf("总用例 %d | 旧引擎 %d (%.1f%%) | 新引擎 %d (%.1f%%)%n",
                total, oldHit, oldRate * 100, newHit, newRate * 100);

        // 门槛：先允许小幅落后，等 S2 切默认前必须追平
        assertThat(newRate).isGreaterThanOrEqualTo(oldRate - 0.05);
    }

    private List<JsonNode> loadCases() throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(EVAL_RESOURCE)) {
            assertThat(in).as("评测集 %s 应存在", EVAL_RESOURCE).isNotNull();
            JsonNode root = MAPPER.readTree(in);
            List<JsonNode> cases = new ArrayList<>();
            root.path("cases").forEach(cases::add);
            return cases;
        }
    }

    /** 只看抽取结果对不对，不看 pending 与落库 */
    private boolean matches(JsonNode c, IntentExtractionResult result) {
        if (result == null) {
            return false;
        }
        List<MemoryCandidate> memories = result.getMemories() == null ? List.of() : result.getMemories();
        List<TaskCandidate> tasks = result.getTasks() == null ? List.of() : result.getTasks();
        String type = c.path("type").asText();

        return switch (type) {
            case "memory" -> {
                if (memories.isEmpty() || !tasks.isEmpty()) {
                    yield false;
                }
                MemoryCandidate m = memories.get(0);
                String key = c.path("expectKey").asText("");
                String contains = c.path("expectValueContains").asText("");
                yield (key.isBlank() || key.equals(m.getKey()))
                        && (contains.isBlank() || (m.getValue() != null && m.getValue().contains(contains)));
            }
            case "task" -> {
                if (tasks.isEmpty()) {
                    yield false;
                }
                TaskCandidate t = tasks.get(0);
                String title = c.path("expectTitleContains").asText("");
                String recurrence = c.path("expectRecurrence").asText("");
                boolean dueOk = !c.path("expectDueHasDate").asBoolean(false)
                        || (t.getDueAt() != null && t.getDueAt().isAfter(LocalDateTime.now().minusDays(2)));
                yield (title.isBlank() || (t.getTitle() != null && t.getTitle().contains(title)))
                        && (recurrence.isBlank() || recurrence.equals(t.getRecurrence()))
                        && dueOk;
            }
            case "mixed" -> {
                String memKey = c.path("expectMemoryKey").asText("");
                String taskTitle = c.path("expectTaskTitleContains").asText("");
                yield memories.stream().anyMatch(m -> memKey.isBlank() || memKey.equals(m.getKey()))
                        && tasks.stream().anyMatch(t -> taskTitle.isBlank()
                                || (t.getTitle() != null && t.getTitle().contains(taskTitle)));
            }
            case "none" -> memories.isEmpty() && tasks.isEmpty();
            // 敏感信息由过滤层拦，确认/拒绝由 IntentDetector 判，都不在抽取环节计分
            default -> true;
        };
    }
}
