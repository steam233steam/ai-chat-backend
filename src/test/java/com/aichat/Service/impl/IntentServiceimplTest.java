package com.aichat.Service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.aichat.Config.MindButlerProperties;
import com.aichat.Dto.ConfirmResult;
import com.aichat.Dto.IntentExtractionResult;
import com.aichat.Dto.IntentOutcome;
import com.aichat.Dto.MemoryCandidate;
import com.aichat.Dto.PendingActionVO;
import com.aichat.Dto.TaskCandidate;
import com.aichat.Dto.UserDto;
import com.aichat.Exception.BusinessException;
import com.aichat.Service.support.IntentCandidateSanitizer;
import com.aichat.Service.support.IntentDailyLimiter;
import com.aichat.Service.IntentExtractor;
import com.aichat.Service.PendingActionService;
import com.aichat.utils.IntentDetector.Intent;
import com.aichat.utils.SensitiveContentFilter;
import com.aichat.utils.UserHolder;

/**
 * IntentService 单测：纯 Mockito，mock PendingActionService + IntentExtractor，
 * 覆盖 提取→写pending→确认文案 / 确认 / 拒绝 / 空候选降级 / 敏感词过滤 / 总开关。
 */
class IntentServiceimplTest {

    private PendingActionService pendingActionService;
    private IntentExtractor intentExtractor;
    private IntentDailyLimiter intentDailyLimiter;
    private IntentCandidateSanitizer candidateSanitizer;
    private MindButlerProperties props;
    private IntentServiceimpl service;

    @BeforeEach
    void setUp() {
        pendingActionService = mock(PendingActionService.class);
        intentExtractor = mock(IntentExtractor.class);
        intentDailyLimiter = mock(IntentDailyLimiter.class);
        when(intentDailyLimiter.tryAcquire(anyString())).thenReturn(true);
        props = new MindButlerProperties();
        props.setEnabled(true);
        service = new IntentServiceimpl();
        ReflectionTestUtils.setField(service, "pendingActionService", pendingActionService);
        ReflectionTestUtils.setField(service, "intentExtractor", intentExtractor);
        ReflectionTestUtils.setField(service, "intentDailyLimiter", intentDailyLimiter);
        candidateSanitizer = new IntentCandidateSanitizer(props);
        ReflectionTestUtils.setField(service, "candidateSanitizer", candidateSanitizer);
        ReflectionTestUtils.setField(service, "props", props);
        UserDto user = new UserDto();
        user.setId("USER_ID_TEST");
        UserHolder.saveUser(user);
    }

    @AfterEach
    void tearDown() {
        UserHolder.removeUser();
    }

    private IntentExtractionResult memoryOnly() {
        return new IntentExtractionResult(
                List.of(new MemoryCandidate("style", "简洁", "stable", 0.9, null)),
                List.of());
    }

    private IntentExtractionResult taskOnly() {
        return new IntentExtractionResult(
                List.of(),
                List.of(new TaskCandidate("create", "交作业", null, "Asia/Shanghai", "once", null)));
    }

    // ---------- 总开关 ----------

    @Test
    void shouldSkipWhenDisabled() {
        props.setEnabled(false);
        IntentOutcome o = service.handle("s-1", "记住我喜欢简洁", 1L);
        assertThat(o.shouldContinueChat()).isTrue();
        verify(pendingActionService, never()).current(anyString());
    }

    // ---------- 普通聊天（无触发词） ----------

    @Test
    void shouldSkipWhenIntentDisabled() {
        props.setIntentEnabled(false);
        IntentOutcome o = service.handle("s-1", "记住我喜欢简洁", 1L);
        assertThat(o.shouldContinueChat()).isTrue();
        verify(pendingActionService, never()).current(anyString());
    }

    @Test
    void shouldReturnChatForPlainMessage() {
        // "今天天气不错" 含日期词"今天"会粗筛成 TASK（宽进），靠提取器空候选降级；
        // 这里用真正无触发词的普通聊天验证直接走 chat 分支
        when(pendingActionService.current("s-1")).thenReturn(null);
        IntentOutcome o = service.handle("s-1", "随便聊聊", 1L);
        assertThat(o.shouldContinueChat()).isTrue();
        verify(intentExtractor, never()).extract(any(), anyString());
    }

    @Test
    void shouldReturnFallbackWhenExtractionEmpty() {
        // 命中触发词但提取器空候选 → 固定兜底，不调聊天模型（v3 8.4）
        when(pendingActionService.current("s-1")).thenReturn(null);
        when(intentExtractor.extract(eq(Intent.TASK), eq("提醒我今天天气不错"))).thenReturn(
                new IntentExtractionResult(List.of(), List.of()));

        IntentOutcome o = service.handle("s-1", "提醒我今天天气不错", 2L);

        assertThat(o.shouldContinueChat()).isFalse();
        assertThat(o.getReply()).contains("没识别出");
        verify(pendingActionService, never()).create(anyString(), anyString(), anyString(), anyLong());
    }

    @Test
    void shouldReturnFallbackWhenDailyLimitExceeded() {
        when(pendingActionService.current("s-1")).thenReturn(null);
        when(intentDailyLimiter.tryAcquire("USER_ID_TEST")).thenReturn(false);

        IntentOutcome o = service.handle("s-1", "记住我喜欢简洁", 2L);

        assertThat(o.shouldContinueChat()).isFalse();
        assertThat(o.getReply()).contains("次数");
        verify(intentExtractor, never()).extract(any(), anyString());
    }

    @Test
    void shouldReturnFallbackWhenPendingCreateConflicts() {
        when(pendingActionService.current("s-1")).thenReturn(null);
        when(intentExtractor.extract(eq(Intent.MEMORY), anyString())).thenReturn(memoryOnly());
        when(pendingActionService.create(anyString(), anyString(), anyString(), anyLong()))
                .thenThrow(new BusinessException(409, "待确认动作正在更新，请重试"));

        IntentOutcome o = service.handle("s-1", "记住我喜欢简洁", 3L);

        assertThat(o.shouldContinueChat()).isFalse();
        assertThat(o.getReply()).contains("冲突");
    }

    // ---------- 提取 → 写 pending → 确认文案 ----------

    @Test
    void shouldExtractMemoryAndCreatePending() {
        when(pendingActionService.current("s-1")).thenReturn(null);
        when(intentExtractor.extract(eq(Intent.MEMORY), eq("记住我喜欢简洁"))).thenReturn(memoryOnly());
        when(pendingActionService.create(eq("s-1"), eq("memory"), anyString(), eq(7L))).thenReturn(11L);

        IntentOutcome o = service.handle("s-1", "记住我喜欢简洁", 7L);

        assertThat(o.isExtracted()).isTrue();
        assertThat(o.shouldContinueChat()).isFalse();
        assertThat(o.getReply()).contains("记住", "简洁");
        // 文案尾部带 pending 标记（前端据此弹窗）
        assertThat(o.getReply()).contains("[[MB:pending=11]]");
        assertThat(o.getPendingId()).isEqualTo(11L);
        verify(pendingActionService).create(eq("s-1"), eq("memory"), anyString(), eq(7L));
    }

    @Test
    void shouldExtractTaskAndCreatePending() {
        when(pendingActionService.current("s-1")).thenReturn(null);
        when(intentExtractor.extract(eq(Intent.TASK), eq("明天 9 点交作业"))).thenReturn(taskOnly());
        when(pendingActionService.create(eq("s-1"), eq("task"), anyString(), eq(8L))).thenReturn(12L);

        IntentOutcome o = service.handle("s-1", "明天 9 点交作业", 8L);

        assertThat(o.isExtracted()).isTrue();
        assertThat(o.getReply()).contains("提醒你", "交作业");
        assertThat(o.getReply()).contains("[[MB:pending=12]]");
        assertThat(o.getPendingId()).isEqualTo(12L);
        verify(pendingActionService).create(eq("s-1"), eq("task"), anyString(), eq(8L));
    }

    @Test
    void shouldDescendToChatWhenExtractionEmpty() {
        when(pendingActionService.current("s-1")).thenReturn(null);
        when(intentExtractor.extract(eq(Intent.MEMORY), anyString())).thenReturn(
                new IntentExtractionResult(List.of(), List.of()));

        IntentOutcome o = service.handle("s-1", "随便记住点啥", 9L);

        assertThat(o.shouldContinueChat()).isFalse();
        assertThat(o.getReply()).contains("没识别出");
        verify(pendingActionService, never()).create(anyString(), anyString(), anyString(), anyLong());
    }

    @Test
    void shouldFilterSensitiveMemoryCandidates() {
        when(pendingActionService.current("s-1")).thenReturn(null);
        // 记忆值含密码特征 → 被 SensitiveContentFilter 拦下，候选全空 → 降级聊天
        IntentExtractionResult withSensitive = new IntentExtractionResult(
                List.of(new MemoryCandidate("goal", "我的密码是 abc123", "dynamic", 0.5, null)),
                List.of());
        when(intentExtractor.extract(eq(Intent.MEMORY), anyString())).thenReturn(withSensitive);
        assertThat(SensitiveContentFilter.detectSensitive("我的密码是 abc123")).isNotNull();

        IntentOutcome o = service.handle("s-1", "记住我的密码", 10L);

        assertThat(o.shouldContinueChat()).isFalse();
        assertThat(o.getReply()).contains("敏感");
        verify(pendingActionService, never()).create(anyString(), anyString(), anyString(), anyLong());
    }

    @Test
    void shouldReturnSensitiveFallbackWhenExtractorReturnsEmpty() {
        when(pendingActionService.current("s-1")).thenReturn(null);
        when(intentExtractor.extract(eq(Intent.MEMORY), anyString()))
                .thenReturn(new IntentExtractionResult(List.of(), List.of()));

        IntentOutcome o = service.handle("s-1", "记住我的密码22333", 10L);

        assertThat(o.shouldContinueChat()).isFalse();
        assertThat(o.getReply()).contains("敏感");
        verify(intentExtractor, never()).extract(any(), anyString());
        verify(pendingActionService, never()).create(anyString(), anyString(), anyString(), anyLong());
    }

    @Test
    void shouldFilterInvalidMemoryKey() {
        when(pendingActionService.current("s-1")).thenReturn(null);
        // key 不在 MemoryKey 枚举里 → 丢弃
        IntentExtractionResult badKey = new IntentExtractionResult(
                List.of(new MemoryCandidate("tech", "用 Java", "stable", 0.8, null)),
                List.of());
        when(intentExtractor.extract(eq(Intent.MEMORY), anyString())).thenReturn(badKey);

        IntentOutcome o = service.handle("s-1", "记住我用 Java", 11L);

        assertThat(o.shouldContinueChat()).isFalse();
        assertThat(o.getReply()).contains("没识别出");
        verify(pendingActionService, never()).create(anyString(), anyString(), anyString(), anyLong());
    }

    @Test
    void shouldReturnUpdateFallbackWithoutCallingExtractor() {
        when(pendingActionService.current("s-1")).thenReturn(null);

        IntentOutcome o = service.handle("s-1", "算了延后十分钟吧", 12L);

        assertThat(o.shouldContinueChat()).isFalse();
        assertThat(o.getReply()).contains("暂不支持");
        verify(intentExtractor, never()).extract(any(), anyString());
        verify(pendingActionService, never()).create(anyString(), anyString(), anyString(), anyLong());
    }

    @Test
    void shouldExpirePendingWhenUserAsksToModifyTask() {
        PendingActionVO pending = new PendingActionVO();
        pending.setId(99L);
        pending.setStatus("pending");
        when(pendingActionService.current("s-1")).thenReturn(pending);

        IntentOutcome o = service.handle("s-1", "延后十分钟", 12L);

        assertThat(o.shouldContinueChat()).isFalse();
        assertThat(o.getReply()).contains("暂不支持");
        verify(pendingActionService).reject(99L);
        verify(intentExtractor, never()).extract(any(), anyString());
    }

    @Test
    void shouldReturnNoPendingFallbackWhenConfirmWithoutPending() {
        when(pendingActionService.current("s-1")).thenReturn(null);

        IntentOutcome o = service.handle("s-1", "确认", 12L);

        assertThat(o.shouldContinueChat()).isFalse();
        assertThat(o.getReply()).contains("没有待确认");
        verify(intentExtractor, never()).extract(any(), anyString());
    }

    @Test
    void shouldSkipNonCreateTaskCandidate() {
        when(pendingActionService.current("s-1")).thenReturn(null);
        IntentExtractionResult upd = new IntentExtractionResult(
                List.of(),
                List.of(new TaskCandidate("update", "改标题", null, "Asia/Shanghai", "once", null)));
        when(intentExtractor.extract(eq(Intent.TASK), anyString())).thenReturn(upd);

        IntentOutcome o = service.handle("s-1", "帮我处理一下这个任务", 12L);

        assertThat(o.shouldContinueChat()).isFalse(); // P1 只允许 create，全部丢弃后固定兜底
        assertThat(o.getReply()).contains("没识别出");
        verify(pendingActionService, never()).create(anyString(), anyString(), anyString(), anyLong());
    }

    @Test
    void shouldShowAbsoluteDateTimeAndRecurrenceInConfirmation() {
        when(pendingActionService.current("s-1")).thenReturn(null);
        TaskCandidate task = new TaskCandidate("create", "交作业",
                java.time.LocalDateTime.of(2026, 11, 3, 9, 0),
                "Asia/Shanghai", "once", null);
        when(intentExtractor.extract(eq(Intent.TASK), anyString()))
                .thenReturn(new IntentExtractionResult(List.of(), List.of(task)));
        when(pendingActionService.create(eq("s-1"), eq("task"), anyString(), eq(8L))).thenReturn(12L);

        IntentOutcome o = service.handle("s-1", "明天 9 点交作业", 8L);

        assertThat(o.getReply()).contains("交作业", "11-03", "周二", "09:00");
    }

    @Test
    void shouldDropWeeklyCandidateWithoutRuleBeforePending() {
        when(pendingActionService.current("s-1")).thenReturn(null);
        TaskCandidate bad = new TaskCandidate("create", "每周一交报告",
                java.time.LocalDateTime.now().plusDays(7), "Asia/Shanghai", "weekly", null);
        when(intentExtractor.extract(eq(Intent.TASK), anyString()))
                .thenReturn(new IntentExtractionResult(List.of(), List.of(bad)));

        IntentOutcome o = service.handle("s-1", "每周一交报告", 9L);

        assertThat(o.shouldContinueChat()).isFalse();
        assertThat(o.getReply()).contains("没识别出");
        verify(pendingActionService, never()).create(anyString(), anyString(), anyString(), anyLong());
    }

    @Test
    void shouldCapMemoryCandidatesAtConfiguredMax() {
        props.setIntentMaxMemories(1);
        when(pendingActionService.current("s-1")).thenReturn(null);
        when(intentExtractor.extract(eq(Intent.MEMORY), anyString())).thenReturn(new IntentExtractionResult(
                List.of(new MemoryCandidate("style", "第一条", "stable", 0.9, null),
                        new MemoryCandidate("goal", "第二条", "stable", 0.9, null)),
                List.of()));
        when(pendingActionService.create(eq("s-1"), eq("memory"), anyString(), eq(7L))).thenReturn(11L);

        service.handle("s-1", "记住我喜欢简洁", 7L);

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(pendingActionService).create(eq("s-1"), eq("memory"), payload.capture(), eq(7L));
        assertThat(payload.getValue()).contains("第一条").doesNotContain("第二条");
    }

    // ---------- 确认 / 拒绝 ----------

    @Test
    void shouldConfirmWhenHasPendingAndConfirmWord() {
        PendingActionVO pending = new PendingActionVO();
        pending.setId(99L);
        pending.setStatus("pending");
        when(pendingActionService.current("s-1")).thenReturn(pending);
        when(pendingActionService.confirm(99L)).thenReturn(
                new ConfirmResult(false, List.of(), List.of()));

        IntentOutcome o = service.handle("s-1", "对", 13L);

        assertThat(o.isConfirmed()).isTrue();
        assertThat(o.getReply()).isNotNull();
        verify(pendingActionService).confirm(99L);
        verify(pendingActionService, never()).reject(anyLong());
    }

    @Test
    void shouldReturnFallbackWhenConfirmExpired() {
        PendingActionVO pending = new PendingActionVO();
        pending.setId(99L);
        pending.setStatus("pending");
        when(pendingActionService.current("s-1")).thenReturn(pending);
        when(pendingActionService.confirm(99L))
                .thenThrow(new BusinessException(409, "该确认已过期，请重新发起"));

        IntentOutcome o = service.handle("s-1", "对", 16L);

        assertThat(o.shouldContinueChat()).isFalse();
        assertThat(o.getReply()).contains("过期");
    }

    @Test
    void shouldBuildHumanReadableConfirmResult() {
        PendingActionVO pending = new PendingActionVO();
        pending.setId(99L);
        pending.setStatus("pending");
        when(pendingActionService.current("s-1")).thenReturn(pending);

        java.util.Map<String, Object> mem = new java.util.HashMap<>();
        mem.put("id", 1L);
        mem.put("key", "style");
        mem.put("value", "简洁");
        java.util.Map<String, Object> task = new java.util.HashMap<>();
        task.put("id", 2L);
        task.put("title", "交作业");
        task.put("dueAt", java.time.LocalDateTime.of(2026, 11, 3, 9, 0));
        task.put("recurrence", "once");
        when(pendingActionService.confirm(99L)).thenReturn(
                new ConfirmResult(false, List.of(mem), List.of(task)));

        IntentOutcome o = service.handle("s-1", "对", 17L);

        assertThat(o.getReply()).contains("风格", "简洁", "交作业", "11-03", "周二", "09:00");
    }

    @Test
    void shouldRejectWhenHasPendingAndRejectWord() {
        PendingActionVO pending = new PendingActionVO();
        pending.setId(99L);
        pending.setStatus("pending");
        when(pendingActionService.current("s-1")).thenReturn(pending);

        IntentOutcome o = service.handle("s-1", "取消", 14L);

        assertThat(o.isRejected()).isTrue();
        verify(pendingActionService).reject(99L);
    }

    @Test
    void shouldDescendToChatWhenConfirmWordButNoPending() {
        // 确认词但没 pending（刚过期）→ 不当成确认，回退聊天
        when(pendingActionService.current("s-1")).thenReturn(null);
        IntentOutcome o = service.handle("s-1", "对", 15L);
        // "对" 无 pending 时 detect=NONE → 走聊天
        assertThat(o.shouldContinueChat()).isTrue();
        verify(pendingActionService, never()).confirm(anyLong());
    }
}
