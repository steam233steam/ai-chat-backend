package com.aichat.Service.intent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

import com.aichat.Dto.IntentExtractionResult;
import com.aichat.utils.IntentDetector;

/**
 * 稳定性体检：同一句话重复问三次，看抽取结果是否一致。
 *
 * <p>「识别不了」有一半是抖动——同一句话这次抽到、下次抽不到。这个测试专门量抖动，
 * 与 {@link IntentProbeTest}（量覆盖面）互补。
 */
@SpringBootTest(properties = "spring.data.redis.repositories.enabled=false")
@ActiveProfiles("local")
class IntentStabilityTest {

    private static final List<String> CASES = List.of(
            "每天九点查一下北京天气",
            "提醒我买牛奶",
            "明天早上八点提醒我交作业",
            "每周一早上八点提醒我跑步",
            "记住我喜欢简洁的回答",
            "帮我看看明天LOL赛程，到点推给我");

    @Autowired
    private IntentToolEngine engine;
    @Autowired
    private Environment environment;

    private boolean realModel;

    @BeforeEach
    void setUp() {
        realModel = "true".equalsIgnoreCase(environment.getProperty("app.mindbutler.eval.real-model", "false"));
        String key = environment.getProperty("spring.ai.openai.api-key", "");
        if (key.isBlank() || key.startsWith("sk-deepseek-placeholder")) {
            realModel = false;
        }
    }

    @Test
    void measureFlakiness() {
        org.junit.jupiter.api.Assumptions.assumeTrue(realModel, "需要真模型开关与 key");

        Map<String, int[]> hit = new LinkedHashMap<>();
        StringBuilder rows = new StringBuilder();
        for (String text : CASES) {
            hit.put(text, new int[1]);
            StringBuilder line = new StringBuilder(String.format("%-24s", text));
            for (int round = 1; round <= 3; round++) {
                IntentExtractionResult r = engine.extract(IntentDetector.detect(text, false), text);
                boolean got = (r.getTasks() != null && !r.getTasks().isEmpty())
                        || (r.getMemories() != null && !r.getMemories().isEmpty());
                if (got) {
                    hit.get(text)[0]++;
                }
                line.append(got ? " Y" : " N");
            }
            rows.append(line).append('\n');
        }
        System.out.println("\n========== 稳定性体检（每句 3 次） ==========");
        System.out.print(rows);
        int full = 0;
        for (Map.Entry<String, int[]> e : hit.entrySet()) {
            if (e.getValue()[0] == 3) {
                full++;
            }
        }
        System.out.printf("三次全中的句子：%d/%d%n", full, CASES.size());
    }
}
