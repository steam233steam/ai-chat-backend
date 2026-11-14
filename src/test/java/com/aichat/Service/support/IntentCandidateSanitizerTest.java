package com.aichat.Service.support;

import com.aichat.Config.MindButlerProperties;
import com.aichat.Dto.TaskCandidate;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 候选校验：重点盯「mcp 任务别被吞」这类历史上踩过的坑。
 */
class IntentCandidateSanitizerTest {

    private IntentCandidateSanitizer sanitizer() {
        MindButlerProperties props = new MindButlerProperties();
        props.setIntentMaxTasks(5);
        props.setIntentMaxMemories(5);
        return new IntentCandidateSanitizer(props);
    }

    private TaskCandidate task(String title, LocalDateTime dueAt, String tool) {
        TaskCandidate c = new TaskCandidate();
        c.setTitle(title);
        c.setDueAt(dueAt);
        c.setTool(tool);
        c.setToolArgs(tool == null ? null : Map.of("city", "北京"));
        return c;
    }

    @Test
    void shouldKeepMcpTaskWithTool() {
        TaskCandidate in = task("查北京天气", LocalDateTime.now().plusHours(1), "weather");

        List<TaskCandidate> out = sanitizer().sanitizeTasks(List.of(in));

        assertThat(out).hasSize(1);
        assertThat(out.get(0).getAction()).isEqualTo("mcp");
        assertThat(out.get(0).getTool()).isEqualTo("weather");
        assertThat(out.get(0).getToolArgs()).containsEntry("city", "北京");
    }

    @Test
    void shouldFallBackToCreateWhenToolMissing() {
        TaskCandidate in = task("交作业", LocalDateTime.now().plusHours(1), null);
        in.setAction("mcp"); // 模型说 mcp 但没给工具

        List<TaskCandidate> out = sanitizer().sanitizeTasks(List.of(in));

        assertThat(out).hasSize(1);
        assertThat(out.get(0).getAction()).isEqualTo("create");
        assertThat(out.get(0).getTool()).isNull();
    }

    @Test
    void shouldDropUnsupportedAction() {
        TaskCandidate in = task("推送到微信", LocalDateTime.now().plusHours(1), null);
        in.setAction("push");

        assertThat(sanitizer().sanitizeTasks(List.of(in))).isEmpty();
    }

    @Test
    void shouldNormalizeToolAlias() {
        TaskCandidate in = task("查天气", LocalDateTime.now().plusHours(1), "weather_report");

        List<TaskCandidate> out = sanitizer().sanitizeTasks(List.of(in));

        assertThat(out).hasSize(1);
        assertThat(out.get(0).getTool()).isEqualTo("weather");
        assertThat(out.get(0).getAction()).isEqualTo("mcp");
    }

    @Test
    void shouldDropDailyTaskWithoutDueAt() {
        TaskCandidate in = task("每天九点查北京天气", null, "weather");
        in.setRecurrence("daily");

        assertThat(sanitizer().sanitizeTasks(List.of(in))).isEmpty();
    }
}
