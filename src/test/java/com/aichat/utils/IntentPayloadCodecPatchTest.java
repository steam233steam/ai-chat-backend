package com.aichat.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.aichat.Dto.TaskCandidate;
import com.aichat.Exception.BusinessException;

/**
 * 改草案的补丁合并：只覆盖补丁里非空的字段，其余原样保留。
 */
class IntentPayloadCodecPatchTest {

    private String draft() {
        TaskCandidate task = new TaskCandidate();
        task.setAction("create");
        task.setTitle("交作业");
        task.setDueAt(LocalDateTime.of(2026, 11, 15, 9, 0));
        task.setRecurrence("once");
        return IntentPayloadCodec.buildPayload(List.of(), List.of(task), "明天九点提醒我交作业");
    }

    private TaskCandidate patchDueAt() {
        TaskCandidate patch = new TaskCandidate();
        patch.setDueAt(LocalDateTime.of(2026, 11, 16, 8, 30));
        return patch;
    }

    @Test
    void shouldOnlyOverrideFieldsPresentInPatch() {
        String merged = IntentPayloadCodec.patchTaskPayload(draft(), patchDueAt());
        List<TaskCandidate> tasks = IntentPayloadCodec.parseCandidates(merged, "tasks", TaskCandidate.class);
        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0).getTitle()).isEqualTo("交作业");
        assertThat(tasks.get(0).getDueAt()).isEqualTo(LocalDateTime.of(2026, 11, 16, 8, 30));
        assertThat(tasks.get(0).getRecurrence()).isEqualTo("once");
    }

    @Test
    void shouldSwitchActionToMcpWhenToolGiven() {
        TaskCandidate patch = new TaskCandidate();
        patch.setTool("weather");
        patch.setToolArgs(Map.of("city", "北京"));

        String merged = IntentPayloadCodec.patchTaskPayload(draft(), patch);
        List<TaskCandidate> tasks = IntentPayloadCodec.parseCandidates(merged, "tasks", TaskCandidate.class);
        assertThat(tasks.get(0).getAction()).isEqualTo("mcp");
        assertThat(tasks.get(0).getTool()).isEqualTo("weather");
        assertThat(tasks.get(0).getToolArgs()).containsEntry("city", "北京");
    }

    @Test
    void shouldDescribeOnlyChangedFields() {
        String before = draft();
        String after = IntentPayloadCodec.patchTaskPayload(before, patchDueAt());
        String diff = IntentPayloadCodec.describeTaskChanges(before, after);
        assertThat(diff).contains("时间").contains("→");
        assertThat(diff).doesNotContain("标题");
        assertThat(IntentPayloadCodec.describeTaskChanges(before, before)).isEmpty();
    }

    @Test
    void shouldRejectPatchWhenDraftHasNoTask() {
        String empty = IntentPayloadCodec.buildPayload(List.of(), List.of(), "随便说说");
        assertThatThrownBy(() -> IntentPayloadCodec.patchTaskPayload(empty, patchDueAt()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("没有可修改的任务");
    }
}
