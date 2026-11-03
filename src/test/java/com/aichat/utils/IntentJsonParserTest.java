package com.aichat.utils;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.aichat.utils.IntentJsonParser.Parsed;

/**
 * IntentJsonParser 纯单测：LLM 结构化 JSON → 候选的解析 + 围栏剥离 + 失败降级。
 */
class IntentJsonParserTest {

    @Test
    void shouldParseMemoryAndTask() {
        String json = "{\n" +
                "  \"memories\":[{\"key\":\"style\",\"value\":\"简洁\",\"memType\":\"stable\",\"confidence\":0.9,\"replacesId\":null}],\n" +
                "  \"tasks\":[{\"action\":\"create\",\"title\":\"交作业\",\"dueAt\":\"2026-11-03T09:00:00\",\"recurrence\":\"once\"}]\n" +
                "}";
        Parsed p = IntentJsonParser.parse(json);
        assertThat(p.memories).hasSize(1);
        assertThat(p.memories.get(0).getKey()).isEqualTo("style");
        assertThat(p.memories.get(0).getValue()).isEqualTo("简洁");
        assertThat(p.tasks).hasSize(1);
        assertThat(p.tasks.get(0).getTitle()).isEqualTo("交作业");
        assertThat(p.tasks.get(0).getDueAt()).isEqualTo(java.time.LocalDateTime.of(2026, 11, 3, 9, 0, 0));
        assertThat(p.isEmpty()).isFalse();
    }

    @Test
    void shouldStripJsonFence() {
        String fenced = "```json\n{\"tasks\":[{\"title\":\"买牛奶\"}]}\n```";
        Parsed p = IntentJsonParser.parse(fenced);
        assertThat(p.tasks).hasSize(1);
        assertThat(p.tasks.get(0).getTitle()).isEqualTo("买牛奶");
    }

    @Test
    void shouldReturnEmptyForBlank() {
        assertThat(IntentJsonParser.parse(null).isEmpty()).isTrue();
        assertThat(IntentJsonParser.parse("").isEmpty()).isTrue();
        assertThat(IntentJsonParser.parse("   ").isEmpty()).isTrue();
    }

    @Test
    void shouldReturnEmptyForInvalidJson() {
        assertThat(IntentJsonParser.parse("这不是 JSON").isEmpty()).isTrue();
    }

    @Test
    void shouldSkipMemoryCandidateWithoutKeyOrValue() {
        String json = "{\"memories\":[{\"value\":\"只有值没键\"},{\"key\":\"style\"},{\"key\":\"style\",\"value\":\"简洁\"}]}";
        Parsed p = IntentJsonParser.parse(json);
        assertThat(p.memories).hasSize(1);
        assertThat(p.memories.get(0).getKey()).isEqualTo("style");
    }

    @Test
    void shouldSkipTaskCandidateWithoutTitle() {
        String json = "{\"tasks\":[{\"dueAt\":\"2026-11-03T09:00:00\"},{\"title\":\"交作业\"}]}";
        Parsed p = IntentJsonParser.parse(json);
        assertThat(p.tasks).hasSize(1);
        assertThat(p.tasks.get(0).getTitle()).isEqualTo("交作业");
    }

    @Test
    void shouldHandleEmptyArrays() {
        Parsed p = IntentJsonParser.parse("{\"memories\":[],\"tasks\":[]}");
        assertThat(p.isEmpty()).isTrue();
    }
}
