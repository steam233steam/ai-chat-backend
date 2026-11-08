package com.aichat.Service.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.aichat.Entity.TaskRun;
import com.aichat.Entity.UserTask;
import com.aichat.Mapper.TaskRunMapper;
import com.aichat.Mapper.UserTaskMapper;
import com.aichat.Service.task.NotifyTaskAction;
import com.aichat.Service.task.TaskAction;
import com.aichat.Service.task.WebhookTaskAction;

class TaskAlarmSchedulerTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-08T01:00:00Z"), ZONE);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 8, 9, 0);

    private UserTaskMapper userTaskMapper;
    private TaskRunMapper taskRunMapper;
    private StringRedisTemplate redis;
    private TaskAlarmScheduler scheduler;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        userTaskMapper = mock(UserTaskMapper.class);
        taskRunMapper = mock(TaskRunMapper.class);
        redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

        List<TaskAction> actions = List.of(new NotifyTaskAction(),
                new WebhookTaskAction(new com.fasterxml.jackson.databind.ObjectMapper()));
        scheduler = new TaskAlarmScheduler(userTaskMapper, taskRunMapper, redis, CLOCK, actions);
    }

    @Test
    void shouldFireDueTaskAndRecordRun() {
        when(userTaskMapper.selectDueAll(any(), any(Integer.class)))
                .thenReturn(List.of(task(7L, "notify")));

        int handled = scheduler.runOnce();

        assertThat(handled).isEqualTo(1);
        ArgumentCaptor<TaskRun> captor = ArgumentCaptor.forClass(TaskRun.class);
        verify(taskRunMapper).insert(captor.capture());
        TaskRun run = captor.getValue();
        assertThat(run.getTaskId()).isEqualTo(7L);
        assertThat(run.getStatus()).isEqualTo("success");
        assertThat(run.getActionType()).isEqualTo("notify");
        assertThat(run.getDetail()).contains("站内提醒");
        verify(userTaskMapper).markNotified(eq(7L), eq(NOW));
    }

    @Test
    void shouldRecordFailureButStillMarkNotified() {
        TaskAction broken = new TaskAction() {
            @Override
            public String name() {
                return "webhook";
            }

            @Override
            public String run(UserTask task, LocalDateTime now) {
                throw new IllegalStateException("外部地址 502");
            }
        };
        scheduler = new TaskAlarmScheduler(userTaskMapper, taskRunMapper, redis, CLOCK, List.of(broken));
        when(userTaskMapper.selectDueAll(any(), any(Integer.class)))
                .thenReturn(List.of(task(8L, "webhook")));

        scheduler.runOnce();

        ArgumentCaptor<TaskRun> captor = ArgumentCaptor.forClass(TaskRun.class);
        verify(taskRunMapper).insert(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("failed");
        assertThat(captor.getValue().getDetail()).contains("502");
        // 失败也标记"响过"，否则每分钟重试一次，把日志刷爆
        verify(userTaskMapper).markNotified(eq(8L), eq(NOW));
    }

    @Test
    void shouldFailWhenActionTypeUnknown() {
        when(userTaskMapper.selectDueAll(any(), any(Integer.class)))
                .thenReturn(List.of(task(9L, "sms")));

        scheduler.runOnce();

        ArgumentCaptor<TaskRun> captor = ArgumentCaptor.forClass(TaskRun.class);
        verify(taskRunMapper).insert(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("failed");
        assertThat(captor.getValue().getDetail()).contains("未注册的动作类型");
    }

    @Test
    void shouldDoNothingWhenNoDueTask() {
        when(userTaskMapper.selectDueAll(any(), any(Integer.class))).thenReturn(List.of());

        assertThat(scheduler.runOnce()).isZero();
        verify(taskRunMapper, never()).insert(any());
        verify(userTaskMapper, never()).markNotified(any(Long.class), any());
    }

    @Test
    void shouldKeepGoingWhenOneTaskThrows() {
        when(userTaskMapper.selectDueAll(any(), any(Integer.class)))
                .thenReturn(List.of(task(1L, "sms"), task(2L, "notify")));

        assertThat(scheduler.runOnce()).isEqualTo(2);
        verify(taskRunMapper, times(2)).insert(any());
        verify(userTaskMapper).markNotified(eq(2L), eq(NOW));
    }

    private UserTask task(long id, String actionType) {
        UserTask t = new UserTask();
        t.setId(id);
        t.setUserId("u1");
        t.setTitle("交作业");
        t.setDueAt(NOW.minusMinutes(5));
        t.setActionType(actionType);
        t.setRecurrence("once");
        t.setStatus("pending");
        return t;
    }
}
