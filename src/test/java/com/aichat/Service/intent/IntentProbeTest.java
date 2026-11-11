package com.aichat.Service.intent;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

import com.aichat.Dto.IntentExtractionResult;
import com.aichat.Dto.MemoryCandidate;
import com.aichat.Dto.TaskCandidate;
import com.aichat.utils.IntentDetector;

/**
 * 口语化任务识别的「体检」：把一批真实口吻的句子喂给工具引擎，逐条打印抽到了什么。
 *
 * <p>它不是断言型测试，而是诊断工具——用来回答「用户觉得识别不了」到底卡在哪：
 * 是模型没调工具（DROP），还是调了但字段空。只在真模型开关打开时跑。
 */
@SpringBootTest(properties = "spring.data.redis.repositories.enabled=false")
@ActiveProfiles("local")
class IntentProbeTest {

    private static final List<String> UTTERANCES = List.of(
            // 没有时间的待办（以前最容易被丢掉的一类）
            "提醒我买牛奶",
            "记得交电费",
            "提醒我给我妈打个电话",
            "提醒我把简历改一下",
            "该买猫粮了，提醒我一下",
            "帮我把这件事记下来：周五之前报销",
            // 有时间的
            "明天九点叫我起床",
            "后天下午三点开会",
            "今晚八点提醒我抢票",
            "三十分钟后提醒我看锅",
            "中午提醒我吃药",
            "三个月后提醒我续费会员",
            // 重复
            "每周一早上八点跑步",
            "每天早上七点半背单词",
            "每周三晚上九点提醒我倒垃圾",
            // 查询 + 推送
            "明天早上查一下北京天气发我",
            "每天九点告诉我今天有什么新闻",
            "帮我看看明天LOL赛程，到点推给我",
            // 记忆
            "记住我喜欢简洁的回答",
            "我朋友都叫我小林",
            "我今年在准备考研",
            // 不该建任务
            "今天好累",
            "你是谁",
            "现在几点了",
            "明天天气怎么样",
            "帮我把这段话翻译成英文：hello world");

    @Autowired
    private IntentToolEngine toolEngine;
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
    void probeRealUtterances() {
        org.junit.jupiter.api.Assumptions.assumeTrue(realModel, "需要真模型开关与 key");

        int tasks = 0;
        int memories = 0;
        int dropped = 0;
        StringBuilder table = new StringBuilder();

        for (String text : UTTERANCES) {
            IntentDetector.Intent intent = IntentDetector.detect(text, false);
            IntentExtractionResult r = toolEngine.extract(intent, text);
            int t = r.getTasks() == null ? 0 : r.getTasks().size();
            int m = r.getMemories() == null ? 0 : r.getMemories().size();
            tasks += t;
            memories += m;
            String detail;
            if (t == 0 && m == 0) {
                dropped++;
                detail = "DROP";
            } else {
                detail = describe(r);
            }
            table.append(String.format("%-28s -> %s%n", text, detail));
        }

        System.out.println("\n========== 口语体检 ==========");
        System.out.print(table);
        System.out.println("--------------------------------------------------");
        System.out.printf("共 %d 句 | 抽到任务 %d | 抽到记忆 %d | 什么都没抽到 %d (%.0f%%)%n",
                UTTERANCES.size(), tasks, memories, dropped, dropped * 100.0 / UTTERANCES.size());
    }

    private String describe(IntentExtractionResult r) {
        StringBuilder sb = new StringBuilder();
        if (r.getTasks() != null) {
            for (TaskCandidate t : r.getTasks()) {
                sb.append("TASK[").append(t.getTitle()).append(" / ")
                        .append(t.getDueAt() == null ? "无时间" : t.getDueAt())
                        .append(" / ").append(t.getRecurrence());
                if (t.getAction() != null && !"create".equals(t.getAction())) {
                    sb.append(" / ").append(t.getAction()).append(':').append(t.getTool());
                }
                sb.append("] ");
            }
        }
        if (r.getMemories() != null) {
            for (MemoryCandidate m : r.getMemories()) {
                sb.append("MEM[").append(m.getKey()).append(":").append(m.getValue()).append("] ");
            }
        }
        return sb.toString().trim();
    }
}
