package com.aichat.Service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import com.aichat.Config.MindButlerProperties;
import com.aichat.Dto.CreateTaskRequest;
import com.aichat.Dto.MemoryCandidate;
import com.aichat.Dto.MemoryVO;
import com.aichat.Dto.PendingActionVO;
import com.aichat.Dto.TaskVO;
import com.aichat.Dto.UserDto;
import com.aichat.Entity.PendingAction;
import com.aichat.Exception.BusinessException;
import com.aichat.Mapper.PendingActionMapper;
import com.aichat.Service.MemoryService;
import com.aichat.Service.TaskService;
import com.aichat.utils.UserHolder;

/**
 * PendingActionService 单测：纯 Mockito（不连 MySQL），反射注入 Mapper/Config/Clock，
 * UserHolder 反射模拟登录态。覆盖 创建/查询/确认/拒绝/过期/幂等/跨用户/分支落库。
 */
class PendingActionServiceimplTest {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    /** 固定"现在"，便于断言 TTL 与懒过期 */
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 3, 9, 0, 0);
    private static final Clock FIXED_CLOCK = Clock.fixed(
            NOW.toInstant(ZoneOffset.ofHours(8)), SHANGHAI);
    private static final String UID = "USER_ID_1234567890";
    private static final String SES = "s-1";

    private PendingActionMapper mapper;
    private MemoryService memoryService;
    private TaskService taskService;
    private MindButlerProperties props;
    private PendingActionServiceimpl service;

    @BeforeEach
    void setUp() {
        mapper = mock(PendingActionMapper.class);
        memoryService = mock(MemoryService.class);
        taskService = mock(TaskService.class);
        props = new MindButlerProperties();
        props.setTaskZone("Asia/Shanghai");
        // 固定 TTL=10min 便于断言
        props.setPendingTtlMinutes(10);
        props.setPendingPayloadMaxBytes(1000);
        service = new PendingActionServiceimpl();
        ReflectionTestUtils.setField(service, "pendingActionMapper", mapper);
        ReflectionTestUtils.setField(service, "memoryService", memoryService);
        ReflectionTestUtils.setField(service, "taskService", taskService);
        ReflectionTestUtils.setField(service, "props", props);
        ReflectionTestUtils.setField(service, "clock", FIXED_CLOCK);
        login();
    }

    private void login() {
        UserDto u = new UserDto();
        u.setId(UID);
        UserHolder.saveUser(u);
    }

    private PendingAction pending(long id, String payload, LocalDateTime expiresAt) {
        PendingAction p = new PendingAction();
        p.setId(id);
        p.setUserId(UID);
        p.setSessionId(SES);
        p.setActionType("mixed");
        p.setPayload(payload);
        p.setStatus("pending");
        p.setExpiresAt(expiresAt);
        p.setSourceMessageId(7L);
        return p;
    }

    // ---------- create ----------

    @Test
    void shouldCreateWithDerivedExpiryAndExpireOld() {
        when(mapper.insert(any(PendingAction.class))).thenAnswer(inv -> {
            PendingAction p = inv.getArgument(0);
            p.setId(55L);
            return 1;
        });

        long id = service.create(SES, "task", "{\"tasks\":[{\"title\":\"交报告\"}]}", 7L);

        assertThat(id).isEqualTo(55L);
        // 旧 pending 先被置 expired
        verify(mapper).expireSession(UID, SES);
        verify(mapper).insert(any(PendingAction.class));
    }

    @Test
    void shouldRejectOversizedPayload() {
        String big = "x".repeat(2000); // > props.pendingPayloadMaxBytes=1000
        assertThatThrownBy(() -> service.create(SES, "task", big, 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("过长");
        verify(mapper, never()).insert(any());
    }

    @Test
    void shouldRejectPayloadOverByteLimitForMultibyteText() {
        props.setPendingPayloadMaxBytes(10);
        String payload = "中文中文中文"; // 18 bytes, 6 chars
        assertThatThrownBy(() -> service.create(SES, "task", payload, 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("过长");
        verify(mapper, never()).insert(any());
    }

    @Test
    void shouldRejectEmptyPayloadOnCreate() {
        assertThatThrownBy(() -> service.create(SES, "task", "{\"tasks\":[]}", 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("待确认内容");
        verify(mapper, never()).insert(any());
    }

    @Test
    void shouldReturn409WhenConcurrentInsertHitsPendingSlot() {
        when(mapper.insert(any(PendingAction.class)))
                .thenThrow(new DuplicateKeyException("uk_pending_slot"));
        assertThatThrownBy(() -> service.create(SES, "task",
                "{\"tasks\":[{\"title\":\"交报告\"}]}", 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("更新");
    }

    // ---------- current ----------

    @Test
    void shouldReturnCurrentPendingWhenFresh() {
        String payload = "{\"memories\":[{\"key\":\"style\",\"value\":\"简洁\"}],"
                + "\"tasks\":[{\"action\":\"create\",\"title\":\"买牛奶\"}]}";
        PendingAction p = pending(9L, payload, NOW.plusMinutes(10));
        when(mapper.selectPendingBySession(UID, SES)).thenReturn(p);

        PendingActionVO vo = service.current(SES);

        assertThat(vo).isNotNull();
        assertThat(vo.getId()).isEqualTo(9L);
        assertThat(vo.getMemories()).hasSize(1);
        assertThat(vo.getTasks()).hasSize(1);
        assertThat(vo.getPreview()).contains("1 条记忆");
        assertThat(vo.getPreview()).contains("1 个任务");
    }

    @Test
    void shouldLazyExpireAndReturnNullWhenDue() {
        PendingAction p = pending(9L, "{}", NOW.minusMinutes(1)); // 已过期
        when(mapper.selectPendingBySession(UID, SES)).thenReturn(p);

        assertThat(service.current(SES)).isNull();
        verify(mapper).markExpiredIfDue(9L, UID, NOW);
        verify(mapper, never()).expireSession(UID, SES);
    }

    @Test
    void shouldReturnNullWhenNoPending() {
        when(mapper.selectPendingBySession(UID, SES)).thenReturn(null);
        assertThat(service.current(SES)).isNull();
    }

    // ---------- confirm：分支落库 ----------

    @Test
    void shouldConfirmMemoryOnly() {
        String payload = "{\"memories\":[{\"key\":\"style\",\"value\":\"简洁\",\"memType\":\"stable\",\"confidence\":0.9}]}";
        PendingAction p = pending(9L, payload, NOW.plusMinutes(5));
        when(mapper.selectById(9L, UID)).thenReturn(p);
        when(mapper.markConfirmed(9L, UID)).thenReturn(1);
        when(memoryService.createWithReplaces(eq("style"), eq("简洁"), eq("stable"),
                eq(0.9), isNull(), eq(7L), isNull()))
                .thenReturn(memoryVO(41L));

        var result = service.confirm(9L);

        assertThat(result.isAlreadyConfirmed()).isFalse();
        assertThat(result.getMemories()).hasSize(1);
        assertThat(result.getTasks()).isEmpty();
        // 缺省 scope=global → sessionScope=null（全局画像）
        verify(memoryService).createWithReplaces(eq("style"), eq("简洁"), eq("stable"),
                eq(0.9), isNull(), eq(7L), isNull());
        verify(taskService, never()).createFromPending(any(CreateTaskRequest.class), anyString());
    }

    @Test
    void shouldConfirmSessionScopedMemory() {
        // P1.1：scope=session 的候选确认时落本会话（pending 的 sessionId）
        String payload = "{\"memories\":[{\"key\":\"style\",\"value\":\"猫娘\",\"memType\":\"stable\","
                + "\"confidence\":0.9,\"scope\":\"session\"}]}";
        PendingAction p = pending(9L, payload, NOW.plusMinutes(5));
        when(mapper.selectById(9L, UID)).thenReturn(p);
        when(mapper.markConfirmed(9L, UID)).thenReturn(1);
        when(memoryService.createWithReplaces(eq("style"), eq("猫娘"), eq("stable"),
                eq(0.9), isNull(), eq(7L), eq(SES)))
                .thenReturn(memoryVO(41L));

        var result = service.confirm(9L);

        assertThat(result.getMemories()).hasSize(1);
        verify(memoryService).createWithReplaces(eq("style"), eq("猫娘"), eq("stable"),
                eq(0.9), isNull(), eq(7L), eq(SES));
    }

    @Test
    void shouldConfirmTaskOnly() {
        String payload = "{\"tasks\":[{\"action\":\"create\",\"title\":\"买牛奶\"}]}";
        PendingAction p = pending(9L, payload, NOW.plusMinutes(5));
        when(mapper.selectById(9L, UID)).thenReturn(p);
        when(mapper.markConfirmed(9L, UID)).thenReturn(1);
        // P1.5：确认链路走 createFromPending（带 created_from_session 溯源）
        when(taskService.createFromPending(any(CreateTaskRequest.class), eq(SES))).thenReturn(taskVO(81L));

        var result = service.confirm(9L);

        assertThat(result.getTasks()).hasSize(1);
        assertThat(result.getMemories()).isEmpty();
        verify(taskService).createFromPending(any(CreateTaskRequest.class), eq(SES));
        verify(memoryService, never()).createWithReplaces(anyString(), anyString(),
                anyString(), anyDouble(), anyLong(), anyLong(), any());
    }

    @Test
    void shouldRejectPendingThatOnlyContainsUnsupportedTaskAction() {
        String payload = "{\"tasks\":[{\"action\":\"update\",\"title\":\"改标题\"}]}";
        PendingAction p = pending(9L, payload, NOW.plusMinutes(5));
        when(mapper.selectById(9L, UID)).thenReturn(p);
        when(mapper.markConfirmed(9L, UID)).thenReturn(1);

        assertThatThrownBy(() -> service.confirm(9L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("待确认内容");

        verify(taskService, never()).createFromPending(any(CreateTaskRequest.class), anyString());
    }

    @Test
    void shouldRejectEmptyPayloadBeforeChangingPendingStatus() {
        PendingAction p = pending(9L, "{}", NOW.plusMinutes(5));
        when(mapper.selectById(9L, UID)).thenReturn(p);
        when(mapper.markConfirmed(9L, UID)).thenReturn(1);

        assertThatThrownBy(() -> service.confirm(9L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("待确认内容");

        verify(mapper).markConfirmed(9L, UID);
    }

    // ---------- confirm：幂等 & 409 ----------

    @Test
    void shouldReturnAlreadyConfirmedWhenDuplicate() {
        PendingAction p = pending(9L, "{}", NOW.plusMinutes(5));
        p.setStatus("confirmed");
        when(mapper.selectById(9L, UID)).thenReturn(p);

        var result = service.confirm(9L);

        assertThat(result.isAlreadyConfirmed()).isTrue();
        verify(mapper, never()).markConfirmed(anyLong(), anyString());
    }

    @Test
    void shouldReturn409WhenRejected() {
        PendingAction p = pending(9L, "{}", NOW.plusMinutes(5));
        p.setStatus("rejected");
        when(mapper.selectById(9L, UID)).thenReturn(p);

        assertThatThrownBy(() -> service.confirm(9L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("拒绝");
    }

    @Test
    void shouldReturn409WhenPendingButExpired() {
        PendingAction p = pending(9L, "{}", NOW.minusMinutes(1)); // 已过期
        when(mapper.selectById(9L, UID)).thenReturn(p);

        assertThatThrownBy(() -> service.confirm(9L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("过期");
        verify(mapper).markExpiredIfDue(9L, UID, NOW);
        verify(mapper, never()).expireSession(UID, SES);
    }

    @Test
    void shouldIdempotentWhenConcurrentConfirmWins() {
        PendingAction p = pending(9L, "{}", NOW.plusMinutes(5));
        when(mapper.selectById(9L, UID)).thenReturn(p);
        when(mapper.markConfirmed(9L, UID)).thenReturn(0); // 被并发抢先
        PendingAction latest = pending(9L, "{}", NOW.plusMinutes(5));
        latest.setStatus("confirmed");
        when(mapper.selectById(9L, UID)).thenReturn(p).thenReturn(latest);

        var result = service.confirm(9L);

        assertThat(result.isAlreadyConfirmed()).isTrue();
    }

    // ---------- confirm：404 跨用户 ----------

    @Test
    void shouldReturn404WhenNotOwner() {
        when(mapper.selectById(99L, UID)).thenReturn(null);
        assertThatThrownBy(() -> service.confirm(99L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不存在");
    }

    // ---------- getById ----------

    @Test
    void shouldGetByIdWhenFresh() {
        PendingAction p = pending(9L, "{}", NOW.plusMinutes(5));
        when(mapper.selectById(9L, UID)).thenReturn(p);

        PendingActionVO vo = service.getById(9L);
        assertThat(vo).isNotNull();
        assertThat(vo.getId()).isEqualTo(9L);
        verify(mapper, never()).expireSession(anyString(), anyString());
    }

    @Test
    void shouldGetByIdReturnNullWhenNotOwner() {
        when(mapper.selectById(404L, UID)).thenReturn(null);
        assertThat(service.getById(404L)).isNull();
    }

    @Test
    void shouldLazyExpireWhenGetByIdPastDue() {
        PendingAction p = pending(9L, "{}", NOW.minusMinutes(1)); // 已过期
        when(mapper.selectById(9L, UID)).thenReturn(p);

        assertThat(service.getById(9L)).isNull();
        verify(mapper).markExpiredIfDue(9L, UID, NOW);
        verify(mapper, never()).expireSession(UID, SES);
    }

    // ---------- reject ----------

    @Test
    void shouldRejectPending() {
        PendingAction p = pending(9L, "{}", NOW.plusMinutes(5));
        when(mapper.selectById(9L, UID)).thenReturn(p);
        when(mapper.markRejected(9L, UID)).thenReturn(1);

        assertThat(service.reject(9L)).isTrue();

        verify(mapper).markRejected(9L, UID);
    }

    @Test
    void shouldSilentlySucceedWhenRejectAlreadyRejected() {
        PendingAction p = pending(9L, "{}", NOW.plusMinutes(5));
        p.setStatus("rejected");
        when(mapper.selectById(9L, UID)).thenReturn(p);

        assertThat(service.reject(9L)).isFalse(); // 幂等，不抛

        verify(mapper, never()).markRejected(anyLong(), anyString());
    }

    @Test
    void shouldSilentlySucceedWhenRejectExpiredStatus() {
        PendingAction p = pending(9L, "{}", NOW.minusMinutes(1));
        p.setStatus("expired");
        when(mapper.selectById(9L, UID)).thenReturn(p);

        assertThat(service.reject(9L)).isFalse();

        verify(mapper, never()).markRejected(anyLong(), anyString());
    }

    @Test
    void shouldSilentlySucceedWhenConcurrentRejectExpires() {
        PendingAction p = pending(9L, "{}", NOW.plusMinutes(5));
        when(mapper.selectById(9L, UID)).thenReturn(p);
        when(mapper.markRejected(9L, UID)).thenReturn(0);
        PendingAction latest = pending(9L, "{}", NOW.plusMinutes(5));
        latest.setStatus("expired");
        when(mapper.selectById(9L, UID)).thenReturn(p).thenReturn(latest);

        assertThat(service.reject(9L)).isFalse();

        verify(mapper).markRejected(9L, UID);
    }

    @Test
    void shouldReturn409WhenRejectAlreadyConfirmed() {
        PendingAction p = pending(9L, "{}", NOW.plusMinutes(5));
        p.setStatus("confirmed");
        when(mapper.selectById(9L, UID)).thenReturn(p);

        assertThatThrownBy(() -> service.reject(9L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已确认");
    }

    @Test
    void shouldSilentlyExpireWhenRejectPastDue() {
        PendingAction p = pending(9L, "{}", NOW.minusMinutes(1)); // 已过期
        when(mapper.selectById(9L, UID)).thenReturn(p);

        assertThat(service.reject(9L)).isFalse(); // 过期等价拒绝，静默

        verify(mapper).markExpiredIfDue(9L, UID, NOW);
        verify(mapper, never()).expireSession(UID, SES);
        verify(mapper, never()).markRejected(anyLong(), anyString());
    }

    @Test
    void shouldReturn404WhenRejectNotOwner() {
        when(mapper.selectById(99L, UID)).thenReturn(null);
        assertThatThrownBy(() -> service.reject(99L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不存在");
    }

    // ---------- 工具 ----------

    private MemoryVO memoryVO(long id) {
        MemoryVO vo = new MemoryVO();
        vo.setId(id);
        vo.setSource("ai");
        return vo;
    }

    private TaskVO taskVO(long id) {
        TaskVO vo = new TaskVO();
        vo.setId(id);
        vo.setTitle("买牛奶");
        vo.setStatus("pending");
        return vo;
    }
}
