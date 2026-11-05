package com.aichat.utils;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.aichat.Dto.TaskVO;

class IntentQueryReplyBuilderTest {

    @Test
    void shouldExplicitlySayNoPendingEvenWhenDoneTasksExist() {
        TaskVO done = task("起床", "done");
        String reply = IntentQueryReplyBuilder.buildTaskList(List.of(), List.of(done), List.of(), 10);

        assertThat(reply).startsWith("你现在没有未完成任务");
        assertThat(reply).contains("起床");
        assertThat(reply).doesNotContain("要完成哪条");
    }

    @Test
    void shouldTellUserToUseTaskListForCompletionAndDeletion() {
        TaskVO pending = task("早八", "pending");
        String reply = IntentQueryReplyBuilder.buildTaskList(List.of(pending), List.of(), List.of(), 10);

        assertThat(reply).contains("早八");
        assertThat(reply).contains("任务完成/删除请到任务列表操作");
        assertThat(reply).doesNotContain("直接说任务名");
    }

    private TaskVO task(String title, String status) {
        TaskVO t = new TaskVO();
        t.setId(1L);
        t.setTitle(title);
        t.setStatus(status);
        t.setDueAt(LocalDateTime.of(2026, 11, 5, 8, 0));
        t.setRecurrence("once");
        return t;
    }
}
