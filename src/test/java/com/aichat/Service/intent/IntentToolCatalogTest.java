package com.aichat.Service.intent;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.model.function.FunctionCallback;

import com.aichat.Dto.MemoryCandidate;
import com.aichat.Dto.TaskCandidate;

/** 工具回调的报文解析与落计划（不涉及模型调用） */
class IntentToolCatalogTest {

    private final IntentToolCatalog catalog = new IntentToolCatalog();

    private FunctionCallback tool(IntentPlan plan, String name) {
        return catalog.build(plan).stream()
                .filter(callback -> name.equals(callback.getName()))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void shouldCreateTaskFromToolArgs() {
        IntentPlan plan = new IntentPlan();
        String ack = tool(plan, "create_task").call(
                "{\"title\":\"交作业\",\"dueAt\":\"2026-11-11T09:00:00\",\"recurrence\":\"once\"}");

        assertThat(ack).contains("交作业");
        assertThat(plan.getTasks()).hasSize(1);
        TaskCandidate task = plan.getTasks().get(0);
        assertThat(task.getTitle()).isEqualTo("交作业");
        assertThat(task.getDueAt()).isEqualTo(LocalDateTime.of(2026, 11, 11, 9, 0));
        assertThat(task.getRecurrence()).isEqualTo("once");
        assertThat(plan.getCalls()).containsExactly("create_task");
    }

    @Test
    void shouldKeepWeeklyRuleAndMcpAction() {
        IntentPlan plan = new IntentPlan();
        tool(plan, "create_task").call("""
                {"title":"查天气","dueAt":"2026-11-12 08:30","recurrence":"weekly",
                 "recurrenceRule":"BYDAY=WE","action":"mcp","tool":"weather","toolArgs":{"city":"北京"}}
                """);

        TaskCandidate task = plan.getTasks().get(0);
        assertThat(task.getRecurrence()).isEqualTo("weekly");
        assertThat(task.getRecurrenceRule()).isEqualTo("BYDAY=WE");
        assertThat(task.getAction()).isEqualTo("mcp");
        assertThat(task.getTool()).isEqualTo("weather");
        assertThat(task.getToolArgs()).containsEntry("city", "北京");
    }

    @Test
    void shouldRejectWhenDueAtMissing() {
        IntentPlan plan = new IntentPlan();
        String ack = tool(plan, "create_task").call("{\"title\":\"交作业\",\"dueAt\":\"明天早上\"}");

        assertThat(ack).contains("参数不合法");
        assertThat(plan.getTasks()).isEmpty();
    }

    @Test
    void shouldRememberWithNormalizedKey() {
        IntentPlan plan = new IntentPlan();
        tool(plan, "remember").call("{\"key\":\"goal\",\"value\":\"年底减重五公斤\",\"memType\":\"dynamic\"}");

        MemoryCandidate memory = plan.getMemories().get(0);
        assertThat(memory.getKey()).isEqualTo("fact");
        assertThat(memory.getMemType()).isEqualTo("dynamic");
        assertThat(plan.getCalls()).containsExactly("remember");
    }

    @Test
    void shouldGiveParsableJsonSchemaForEveryTool() throws Exception {
        for (FunctionCallback tool : catalog.build(new IntentPlan())) {
            String schema = tool.getInputTypeSchema();
            // 模型调用链会把 schema 当 JSON 解析，多一个未转义引号就整轮失败
            assertThat(new com.fasterxml.jackson.databind.ObjectMapper().readTree(schema).path("type").asText())
                    .as("工具 %s 的 inputSchema 必须是合法 JSON", tool.getName())
                    .isEqualTo("object");
        }
    }

    @Test
    void shouldAcceptMultipleCallsInOneSentence() {
        IntentPlan plan = new IntentPlan();
        List<FunctionCallback> tools = catalog.build(plan);
        tools.stream().filter(c -> "remember".equals(c.getName())).findFirst().orElseThrow()
                .call("{\"key\":\"style\",\"value\":\"喜欢简洁\"}");
        tools.stream().filter(c -> "create_task".equals(c.getName())).findFirst().orElseThrow()
                .call("{\"title\":\"交论文\",\"dueAt\":\"2026-11-11T09:00:00\",\"recurrence\":\"once\"}");

        assertThat(plan.getMemories()).hasSize(1);
        assertThat(plan.getTasks()).hasSize(1);
        assertThat(plan.isEmpty()).isFalse();
    }
}
