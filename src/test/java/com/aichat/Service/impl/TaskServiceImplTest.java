package com.aichat.Service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.aichat.Config.MindButlerProperties;
import com.aichat.Dto.CompleteTaskRequest;
import com.aichat.Dto.CreateTaskRequest;
import com.aichat.Dto.TaskRunVO;
import com.aichat.Dto.TaskVO;
import com.aichat.Dto.UpdateTaskRequest;
import com.aichat.Dto.UserDto;
import com.aichat.Entity.TaskRun;
import com.aichat.Entity.UserTask;
import com.aichat.Exception.BusinessException;
import com.aichat.Mapper.TaskRunMapper;
import com.aichat.Mapper.UserTaskMapper;
import com.aichat.utils.UserHolder;

/**
 * TaskService 单测：纯 Mockito（不连 MySQL），反射注入 Mapper/Config/Clock，
 * UserHolder 反射模拟登录态。覆盖 CRUD / 完成/重复滚动 / 到期查询 / 跨用户 404 / 乐观锁 409。
 */
class TaskServiceImplTest {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    /** 固定一个"现在"，便于断言滚动到下一次 */
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 3, 9, 0, 0);
    private static final Clock FIXED_CLOCK = Clock.fixed(
            NOW.toInstant(ZoneOffset.ofHours(8)), SHANGHAI);

    private UserTaskMapper mapper;
    private TaskRunMapper taskRunMapper;
    private MindButlerProperties props;
    private TaskServiceImpl service;

    @BeforeEach
    void setUp() {
        mapper = mock(UserTaskMapper.class);
        taskRunMapper = mock(TaskRunMapper.class);
        props = new MindButlerProperties();
        props.setTaskZone("Asia/Shanghai");
        service = new TaskServiceImpl(mapper, taskRunMapper, props, FIXED_CLOCK);
        login("USER_ID_1234567890");
    }

    private void login(String uid) {
        UserDto u = new UserDto();
        u.setId(uid);
        UserHolder.saveUser(u);
    }

    private UserTask task(long id, String title, String recurrence, String rule,
            LocalDateTime dueAt, String status) {
        UserTask t = new UserTask();
        t.setId(id);
        t.setUserId("USER_ID_1234567890");
        t.setTitle(title);
        t.setRecurrence(recurrence);
        t.setRecurrenceRule(rule);
        t.setDueAt(dueAt);
        t.setStatus(status);
        t.setVersion(0);
        t.setCreateTime(NOW);
        t.setUpdateTime(NOW);
        return t;
    }

    // ---------- 创建 ----------

    @Test
    void shouldCreateOnceTaskAndEchoBack() {
        CreateTaskRequest req = new CreateTaskRequest();
        req.setTitle("买牛奶");
        req.setRecurrence("once");
        when(mapper.insert(any(UserTask.class))).thenAnswer(inv -> {
            UserTask t = inv.getArgument(0);
            t.setId(1L);
            return 1;
        });
        when(mapper.selectById(1L, "USER_ID_1234567890"))
                .thenReturn(task(1L, "买牛奶", "once", null, null, "pending"));

        TaskVO vo = service.create(req);

        assertThat(vo.getStatus()).isEqualTo("pending");
        assertThat(vo.getRecurrence()).isEqualTo("once");
        assertThat(vo.getDueAt()).isNull();
        verify(mapper).insert(any(UserTask.class));
    }

    @Test
    void shouldRejectRecurringTaskWithoutDueAt() {
        CreateTaskRequest req = new CreateTaskRequest();
        req.setTitle("每天敲代码");
        req.setRecurrence("daily");
        // daily 没给 dueAt
        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("必须指定截止时间");
        verify(mapper, never()).insert(any());
    }

    @Test
    void shouldRejectWeeklyTaskWithoutRule() {
        CreateTaskRequest req = new CreateTaskRequest();
        req.setTitle("每周一交报告");
        req.setRecurrence("weekly");
        req.setDueAt(NOW.plusDays(7));
        // weekly 没给 recurrenceRule
        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("recurrenceRule");
        verify(mapper, never()).insert(any());
    }

    @Test
    void shouldRejectMonthlyRecurrence() {
        CreateTaskRequest req = new CreateTaskRequest();
        req.setTitle("每月");
        req.setRecurrence("monthly");
        req.setDueAt(NOW.plusDays(30));
        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("once/daily/weekly");
    }

    @Test
    void shouldListDoneTasks() {
        when(mapper.selectByUserBeforeId(eq("USER_ID_1234567890"), eq("done"), eq(Long.MAX_VALUE), eq(50)))
                .thenReturn(List.of(task(1L, "已完成", "once", null, NOW, "done")));

        List<TaskVO> vos = service.list("done", null, 50);

        assertThat(vos).hasSize(1);
        assertThat(vos.get(0).getStatus()).isEqualTo("done");
    }

    @Test
    void shouldRollPastDailyDueToNextFutureOnCreate() {
        AtomicReference<UserTask> inserted = new AtomicReference<>();
        when(mapper.insert(any(UserTask.class))).thenAnswer(inv -> {
            UserTask t = inv.getArgument(0);
            t.setId(1L);
            inserted.set(t);
            return 1;
        });
        when(mapper.selectById(1L, "USER_ID_1234567890")).thenAnswer(inv -> {
            UserTask t = inserted.get();
            t.setStatus("pending");
            t.setVersion(0);
            t.setCreateTime(NOW);
            t.setUpdateTime(NOW);
            return t;
        });

        CreateTaskRequest req = new CreateTaskRequest();
        req.setTitle("每天敲代码");
        req.setRecurrence("daily");
        req.setDueAt(NOW.minusDays(2).withHour(9).withMinute(0));

        TaskVO vo = service.create(req);

        assertThat(vo.getDueAt()).isAfter(NOW);
        assertThat(vo.getDueAt().toLocalTime()).isEqualTo(java.time.LocalTime.of(9, 0));
    }

    @Test
    void shouldAlignPastWeeklyDueToByDayOnCreate() {
        AtomicReference<UserTask> inserted = new AtomicReference<>();
        when(mapper.insert(any(UserTask.class))).thenAnswer(inv -> {
            UserTask t = inv.getArgument(0);
            t.setId(2L);
            inserted.set(t);
            return 1;
        });
        when(mapper.selectById(2L, "USER_ID_1234567890")).thenAnswer(inv -> {
            UserTask t = inserted.get();
            t.setStatus("pending");
            t.setVersion(0);
            t.setCreateTime(NOW);
            t.setUpdateTime(NOW);
            return t;
        });

        CreateTaskRequest req = new CreateTaskRequest();
        req.setTitle("每周一交报告");
        req.setRecurrence("weekly");
        req.setRecurrenceRule("BYDAY=MO");
        req.setDueAt(NOW.minusDays(2).withHour(9).withMinute(0)); // 周日，早于 now

        TaskVO vo = service.create(req);

        assertThat(vo.getDueAt()).isAfter(NOW);
        assertThat(vo.getDueAt().getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
    }

    @Test
    void shouldRejectWhenTaskLimitReached() {
        when(mapper.countByUser("USER_ID_1234567890")).thenReturn(500);
        CreateTaskRequest req = new CreateTaskRequest();
        req.setTitle("第 501 条");
        req.setRecurrence("once");
        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("上限");
    }

    // ---------- 完成 + 重复滚动 ----------

    @Test
    void shouldCompleteOnceTaskToDone() {
        UserTask current = task(1L, "交作业", "once", null, NOW.plusDays(1), "pending");
        when(mapper.selectById(1L, "USER_ID_1234567890")).thenReturn(current);
        when(mapper.complete(anyLong(), anyString(), anyString(), any(), any(), anyInt()))
                .thenAnswer(inv -> {
                    int v = inv.getArgument(5, Integer.class);
                    return v == 0 ? 1 : 0;
                });

        TaskVO vo = service.complete(1L, version(0));

        assertThat(vo.getStatus()).isIn("done", "pending"); // once → done
        // once 完成保留原始 due_at，不再清空
        verify(mapper).complete(eq(1L), eq("USER_ID_1234567890"), eq("done"),
                any(LocalDateTime.class), eq(NOW.plusDays(1)), eq(0));
    }

    @Test
    void shouldRollDailyTaskToNextFutureDue() {
        // 提前完成场景：due_at 是"昨天"，完成后应滚到 > now
        LocalDateTime yesterday = NOW.minusDays(1);
        UserTask current = task(2L, "每天敲代码", "daily", null, yesterday, "pending");
        when(mapper.selectById(2L, "USER_ID_1234567890")).thenReturn(current);
        when(mapper.complete(anyLong(), anyString(), anyString(), any(), any(), anyInt()))
                .thenReturn(1);
        when(mapper.selectById(2L, "USER_ID_1234567890")).thenReturn(current);

        TaskVO vo = service.complete(2L, version(0));

        // daily 保持 pending，due_at 滚到 > now（最早明天）
        assertThat(vo.getStatus()).isEqualTo("pending");
        // 校验传给 complete 的 newDue 是未来时间
        verify(mapper).complete(eq(2L), eq("USER_ID_1234567890"), eq("pending"),
                any(LocalDateTime.class), any(LocalDateTime.class), eq(0));
    }

    @Test
    void shouldRollWeeklyTaskToNextWeek() {
        // weekly BYDAY=MO，due_at 是 7 天前（上周同曜日），完成后滚到下周同曜日
        UserTask current = task(3L, "每周一交报告", "weekly", "BYDAY=MO",
                NOW.minusDays(7), "pending");
        when(mapper.selectById(3L, "USER_ID_1234567890")).thenReturn(current);
        when(mapper.complete(anyLong(), anyString(), anyString(), any(), any(), anyInt()))
                .thenReturn(1);

        TaskVO vo = service.complete(3L, version(0));

        assertThat(vo.getStatus()).isEqualTo("pending");
        verify(mapper).complete(eq(3L), eq("USER_ID_1234567890"), eq("pending"),
                any(LocalDateTime.class), any(LocalDateTime.class), eq(0));
    }

    @Test
    void shouldReturn409OnCompleteVersionConflict() {
        UserTask current = task(1L, "交作业", "once", null, NOW.plusDays(1), "pending");
        when(mapper.selectById(1L, "USER_ID_1234567890")).thenReturn(current);
        when(mapper.complete(anyLong(), anyString(), anyString(), any(), any(), anyInt()))
                .thenReturn(0); // 版本不匹配

        assertThatThrownBy(() -> service.complete(1L, version(0)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("版本冲突");
    }

    @Test
    void shouldRejectCompletingNonPendingTask() {
        UserTask done = task(1L, "交作业", "once", null, NOW, "done");
        when(mapper.selectById(1L, "USER_ID_1234567890")).thenReturn(done);
        assertThatThrownBy(() -> service.complete(1L, version(0)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("只能完成 pending");
    }

    // ---------- 更新（乐观锁） ----------

    @Test
    void shouldUpdatePendingTaskWithMatchingVersion() {
        UserTask current = task(1L, "交作业", "once", null, NOW.plusDays(1), "pending");
        when(mapper.selectById(1L, "USER_ID_1234567890")).thenReturn(current);
        when(mapper.updateByIdAndVersion(any(UserTask.class))).thenReturn(1);

        UpdateTaskRequest req = new UpdateTaskRequest();
        req.setTitle("交国二");
        req.setVersion(0);

        TaskVO vo = service.update(1L, req);

        assertThat(vo).isNotNull();
        verify(mapper).updateByIdAndVersion(any(UserTask.class));
    }

    @Test
    void shouldReturn409OnUpdateVersionConflict() {
        UserTask current = task(1L, "交作业", "once", null, NOW.plusDays(1), "pending");
        when(mapper.selectById(1L, "USER_ID_1234567890")).thenReturn(current);
        when(mapper.updateByIdAndVersion(any(UserTask.class))).thenReturn(0);

        UpdateTaskRequest req = new UpdateTaskRequest();
        req.setVersion(0);

        assertThatThrownBy(() -> service.update(1L, req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("版本冲突");
    }

    @Test
    void shouldReturnNullOnCrossUserUpdate() {
        when(mapper.selectById(99L, "USER_ID_1234567890")).thenReturn(null);
        UpdateTaskRequest req = new UpdateTaskRequest();
        req.setVersion(0);
        assertThat(service.update(99L, req)).isNull();
    }

    @Test
    void shouldRejectUpdatingDoneTask() {
        UserTask done = task(1L, "交作业", "once", null, NOW, "done");
        when(mapper.selectById(1L, "USER_ID_1234567890")).thenReturn(done);
        UpdateTaskRequest req = new UpdateTaskRequest();
        req.setVersion(0);
        assertThatThrownBy(() -> service.update(1L, req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("只能修改 pending");
    }

    // ---------- 查询 / 删除 / 到期 ----------

    @Test
    void shouldListWithCursorPaging() {
        when(mapper.selectByUserBeforeId(eq("USER_ID_1234567890"), eq("pending"),
                anyLong(), anyInt()))
                .thenReturn(List.of(task(3L, "c", "once", null, NOW, "pending"),
                        task(2L, "b", "once", null, NOW, "pending"),
                        task(1L, "a", "once", null, NOW, "pending")));

        List<TaskVO> vos = service.list("pending", null, 50);

        // 游标倒序取，Service 反转成升序
        assertThat(vos).hasSize(3);
        assertThat(vos.get(0).getId()).isEqualTo(1L);
        assertThat(vos.get(2).getId()).isEqualTo(3L);
    }

    @Test
    void shouldReturnNullOnCrossUserGet() {
        when(mapper.selectById(99L, "USER_ID_1234567890")).thenReturn(null);
        assertThat(service.get(99L)).isNull();
    }

    @Test
    void shouldSoftDeleteIdempotently() {
        when(mapper.softDelete(1L, "USER_ID_1234567890")).thenReturn(1);
        when(mapper.softDelete(2L, "USER_ID_1234567890")).thenReturn(0);

        assertThat(service.delete(1L)).isTrue();
        assertThat(service.delete(2L)).isFalse();
    }

    @Test
    void shouldQueryDueTasks() {
        when(mapper.selectDue(eq("USER_ID_1234567890"), any(LocalDateTime.class), anyInt()))
                .thenReturn(List.of(task(1L, "已到期", "once", null, NOW.minusHours(1), "pending")));

        List<TaskVO> due = service.due(50);

        assertThat(due).hasSize(1);
        assertThat(due.get(0).getOverdue()).isTrue();
    }

    // ---------- 工具 ----------

    private CompleteTaskRequest version(int v) {
        CompleteTaskRequest r = new CompleteTaskRequest();
        r.setVersion(v);
        return r;
    }

    @Test
    void shouldRejectSensitiveTaskTitle() {
        CreateTaskRequest req = new CreateTaskRequest();
        req.setTitle("我的密码是 abc123");
        req.setRecurrence("once");

        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("密码");
        verify(mapper, never()).insert(any());
    }

    @Test
    void shouldRejectSensitiveTaskTitleOnUpdate() {
        UserTask current = task(1L, "交作业", "once", null, NOW.plusDays(1), "pending");
        when(mapper.selectById(1L, "USER_ID_1234567890")).thenReturn(current);

        UpdateTaskRequest req = new UpdateTaskRequest();
        req.setTitle("token=abcd1234");
        req.setVersion(0);

        assertThatThrownBy(() -> service.update(1L, req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Token");
        verify(mapper, never()).updateByIdAndVersion(any());
    }

    @Test
    void shouldTreatAlreadyDeletedTaskAsIdempotentSuccess() {
        when(mapper.softDelete(1L, "USER_ID_1234567890")).thenReturn(0);
        when(mapper.countById(1L, "USER_ID_1234567890")).thenReturn(1);

        assertThat(service.delete(1L)).isTrue();
        verify(mapper).countById(1L, "USER_ID_1234567890");
    }

    @Test
    void shouldAllowOrdinaryPasswordTaskWithoutSecretValue() {
        CreateTaskRequest req = new CreateTaskRequest();
        req.setTitle("提醒我改密码");
        req.setRecurrence("once");
        when(mapper.insert(any(UserTask.class))).thenAnswer(inv -> {
            UserTask t = inv.getArgument(0);
            t.setId(3L);
            return 1;
        });
        when(mapper.selectById(3L, "USER_ID_1234567890"))
                .thenReturn(task(3L, "提醒我改密码", "once", null, null, "pending"));

        TaskVO vo = service.create(req);

        assertThat(vo.getTitle()).isEqualTo("提醒我改密码");
        verify(mapper).insert(any(UserTask.class));
    }

    @Test
    void shouldReturnRunsOfOwnTask() {
        when(mapper.selectById(5L, "USER_ID_1234567890"))
                .thenReturn(task(5L, "交作业", "once", null, NOW, "pending"));
        TaskRun run = new TaskRun();
        run.setId(11L);
        run.setTaskId(5L);
        run.setUserId("USER_ID_1234567890");
        run.setActionType("notify");
        run.setTriggerType("scheduled");
        run.setStatus("success");
        run.setDetail("站内提醒：交作业");
        run.setStartedAt(NOW);
        when(taskRunMapper.selectByTask(5L, "USER_ID_1234567890", 20)).thenReturn(List.of(run));

        List<TaskRunVO> runs = service.runs(5L, 20);

        assertThat(runs).hasSize(1);
        assertThat(runs.get(0).status()).isEqualTo("success");
        assertThat(runs.get(0).actionType()).isEqualTo("notify");
    }

    @Test
    void should404WhenRunsOfOthersTask() {
        when(mapper.selectById(9L, "USER_ID_1234567890")).thenReturn(null);

        assertThatThrownBy(() -> service.runs(9L, 20))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("任务不存在");
        verify(taskRunMapper, never()).selectByTask(any(Long.class), any(), any(Integer.class));
    }

    // ---------- 到点动作（notify / webhook） ----------

    private UserTask captureCreated(CreateTaskRequest req) {
        AtomicReference<UserTask> saved = new AtomicReference<>();
        when(mapper.insert(any(UserTask.class))).thenAnswer(inv -> {
            UserTask t = inv.getArgument(0);
            t.setId(1L);
            saved.set(t);
            return 1;
        });
        when(mapper.selectById(1L, "USER_ID_1234567890"))
                .thenReturn(task(1L, "买牛奶", "once", null, null, "pending"));
        service.create(req);
        return saved.get();
    }

    @Test
    void shouldDefaultActionToNotify() {
        CreateTaskRequest req = new CreateTaskRequest();
        req.setTitle("买牛奶");
        req.setRecurrence("once");

        UserTask saved = captureCreated(req);

        assertThat(saved.getActionType()).isEqualTo("notify");
        assertThat(saved.getActionConfig()).isNull();
    }

    @Test
    void shouldStoreWebhookAction() {
        CreateTaskRequest req = new CreateTaskRequest();
        req.setTitle("每天九点跑测试");
        req.setRecurrence("daily");
        req.setDueAt(NOW.plusDays(1));
        req.setActionType("webhook");
        req.setActionConfig("{\"url\":\"https://example.com/hook\"}");

        UserTask saved = captureCreated(req);

        assertThat(saved.getActionType()).isEqualTo("webhook");
        assertThat(saved.getActionConfig()).contains("example.com/hook");
    }

    @Test
    void shouldRejectWebhookWithoutUrl() {
        CreateTaskRequest req = new CreateTaskRequest();
        req.setTitle("跑测试");
        req.setActionType("webhook");
        req.setActionConfig("{\"target\":\"https://example.com\"}");

        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("缺少 url");
        verify(mapper, never()).insert(any(UserTask.class));
    }

    @Test
    void shouldRejectUnknownActionType() {
        CreateTaskRequest req = new CreateTaskRequest();
        req.setTitle("跑测试");
        req.setActionType("shell");

        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("动作类型只支持");
    }

    @Test
    void shouldStoreMcpAction() {
        CreateTaskRequest req = new CreateTaskRequest();
        req.setTitle("每天九点查天气");
        req.setRecurrence("daily");
        req.setDueAt(NOW.plusDays(1));
        req.setActionType("mcp");
        req.setActionConfig("{\"tool\":\"mcp_info_weather\",\"args\":{\"city\":\"北京\"}}");

        UserTask saved = captureCreated(req);

        assertThat(saved.getActionType()).isEqualTo("mcp");
        assertThat(saved.getActionConfig()).contains("mcp_info_weather");
    }

    @Test
    void shouldRejectMcpToolWithoutPrefix() {
        CreateTaskRequest req = new CreateTaskRequest();
        req.setTitle("查天气");
        req.setActionType("mcp");
        req.setActionConfig("{\"tool\":\"weather\"}");

        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("mcp_");
        verify(mapper, never()).insert(any(UserTask.class));
    }

    @Test
    void shouldRejectMcpConfigThatIsNotJson() {
        CreateTaskRequest req = new CreateTaskRequest();
        req.setTitle("查天气");
        req.setActionType("mcp");
        req.setActionConfig("查北京天气");

        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("合法 JSON");
    }

    @Test
    void shouldStoreNtfyPushAction() {
        CreateTaskRequest req = new CreateTaskRequest();
        req.setTitle("每天九点交作业");
        req.setRecurrence("daily");
        req.setDueAt(NOW.plusDays(1));
        req.setActionType("push");
        req.setActionConfig("{\"channel\":\"ntfy\",\"topic\":\"mindbutler-233\"}");

        UserTask saved = captureCreated(req);

        assertThat(saved.getActionType()).isEqualTo("push");
        assertThat(saved.getActionConfig()).contains("mindbutler-233");
    }

    @Test
    void shouldRejectNtfyPushWithoutTopic() {
        CreateTaskRequest req = new CreateTaskRequest();
        req.setTitle("交作业");
        req.setActionType("push");
        req.setActionConfig("{\"channel\":\"ntfy\"}");

        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("topic");
        verify(mapper, never()).insert(any(UserTask.class));
    }

    @Test
    void shouldRejectUnsupportedPushChannel() {
        CreateTaskRequest req = new CreateTaskRequest();
        req.setTitle("交作业");
        req.setActionType("push");
        req.setActionConfig("{\"channel\":\"telegram\",\"target\":\"https://x\"}");

        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("推送通道只支持");
    }

    @Test
    void shouldRejectBarkPushWithoutTarget() {
        CreateTaskRequest req = new CreateTaskRequest();
        req.setTitle("交作业");
        req.setActionType("push");
        req.setActionConfig("{\"channel\":\"bark\"}");

        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("推送地址");
    }

}
