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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.aichat.Config.MindButlerProperties;
import com.aichat.Dto.ConfirmResult;
import com.aichat.Dto.MemoryCandidate;
import com.aichat.Dto.PendingActionVO;
import com.aichat.Dto.TaskCandidate;
import com.aichat.Service.IntentExtractor;
import com.aichat.Service.IntentOutcome;
import com.aichat.Service.PendingActionService;
import com.aichat.utils.IntentDetector.Intent;
import com.aichat.utils.IntentJsonParser;
import com.aichat.utils.IntentJsonParser.Parsed;
import com.aichat.utils.SensitiveContentFilter;

/**
 * IntentService 单测：纯 Mockito，mock PendingActionService + IntentExtractor，
 * 覆盖 提取→写pending→确认文案 / 确认 / 拒绝 / 空候选降级 / 敏感词过滤 / 总开关。
 */
class IntentServiceimplTest {

    private PendingActionService pendingActionService;
    private IntentExtractor intentExtractor;
    private MindButlerProperties props;
    private IntentServiceimpl service;

    @BeforeEach
    void setUp() {
        pendingActionService = mock(PendingActionService.class);
        intentExtractor = mock(IntentExtractor.class);
        props = new MindButlerProperties();
        props.setEnabled(true);
        service = new IntentServiceimpl();
        ReflectionTestUtils.setField(service, "pendingActionService", pendingActionService);
        ReflectionTestUtils.setField(service, "intentExtractor", intentExtractor);
        ReflectionTestUtils.setField(service, "props", props);
    }

    private Parsed memoryOnly() {
        return new Parsed(
                List.of(new MemoryCandidate("style", "简洁", "stable", 0.9, null)),
                List.of());
    }

    private Parsed taskOnly() {
        return new Parsed(
                List.of(),
                List.of(new TaskCandidate("create", "交作业", null, "Asia/Shanghai", "once", null)));
    }

    // ---------- 总开关 ----------

    @Test
    void shouldSkipWhenDisabled() {
        props.setEnabled(false);
        IntentOutcome o = service.handle("s-1", "记住我喜欢简洁", 1L);
        assertThat(o.shouldExtract).isTrue();
        verify(pendingActionService, never()).current(anyString());
    }

    // ---------- 普通聊天（无触发词） ----------

    @Test
    void shouldReturnChatForPlainMessage() {
        // "今天天气不错" 含日期词"今天"会粗筛成 TASK（宽进），靠提取器空候选降级；
        // 这里用真正无触发词的普通聊天验证直接走 chat 分支
        when(pendingActionService.current("s-1")).thenReturn(null);
        IntentOutcome o = service.handle("s-1", "随便聊聊", 1L);
        assertThat(o.shouldExtract).isTrue();
        verify(intentExtractor, never()).extract(any(), anyString());
    }

    @Test
    void shouldDescendToChatWhenTriggleButExtractionEmpty() {
        // 日期词"今天"误触发 TASK，但提取器返回空候选 → 降级正常聊天（粗筛宽进 + 提取兜底的安全边界）
        when(pendingActionService.current("s-1")).thenReturn(null);
        when(intentExtractor.extract(eq(Intent.TASK), eq("今天天气不错"))).thenReturn(
                new Parsed(List.of(), List.of()));

        IntentOutcome o = service.handle("s-1", "今天天气不错", 2L);

        assertThat(o.shouldExtract).isTrue();
        verify(intentExtractor).extract(eq(Intent.TASK), eq("今天天气不错"));
        verify(pendingActionService, never()).create(anyString(), anyString(), anyString(), anyLong());
    }

    // ---------- 提取 → 写 pending → 确认文案 ----------

    @Test
    void shouldExtractMemoryAndCreatePending() {
        when(pendingActionService.current("s-1")).thenReturn(null);
        when(intentExtractor.extract(eq(Intent.MEMORY), eq("记住我喜欢简洁"))).thenReturn(memoryOnly());
        when(pendingActionService.create(eq("s-1"), eq("memory"), anyString(), eq(7L))).thenReturn(11L);

        IntentOutcome o = service.handle("s-1", "记住我喜欢简洁", 7L);

        assertThat(o.extracted).isTrue();
        assertThat(o.shouldExtract).isFalse();
        assertThat(o.reply).contains("记忆");
        verify(pendingActionService).create(eq("s-1"), eq("memory"), anyString(), eq(7L));
    }

    @Test
    void shouldExtractTaskAndCreatePending() {
        when(pendingActionService.current("s-1")).thenReturn(null);
        when(intentExtractor.extract(eq(Intent.TASK), eq("明天 9 点交作业"))).thenReturn(taskOnly());
        when(pendingActionService.create(eq("s-1"), eq("task"), anyString(), eq(8L))).thenReturn(12L);

        IntentOutcome o = service.handle("s-1", "明天 9 点交作业", 8L);

        assertThat(o.extracted).isTrue();
        assertThat(o.reply).contains("任务");
        verify(pendingActionService).create(eq("s-1"), eq("task"), anyString(), eq(8L));
    }

    @Test
    void shouldDescendToChatWhenExtractionEmpty() {
        when(pendingActionService.current("s-1")).thenReturn(null);
        when(intentExtractor.extract(eq(Intent.MEMORY), anyString())).thenReturn(
                new Parsed(List.of(), List.of()));

        IntentOutcome o = service.handle("s-1", "随便记住点啥", 9L);

        assertThat(o.shouldExtract).isTrue();
        verify(pendingActionService, never()).create(anyString(), anyString(), anyString(), anyLong());
    }

    @Test
    void shouldFilterSensitiveMemoryCandidates() {
        when(pendingActionService.current("s-1")).thenReturn(null);
        // 记忆值含密码特征 → 被 SensitiveContentFilter 拦下，候选全空 → 降级聊天
        Parsed withSensitive = new Parsed(
                List.of(new MemoryCandidate("goal", "我的密码是 abc123", "dynamic", 0.5, null)),
                List.of());
        when(intentExtractor.extract(eq(Intent.MEMORY), anyString())).thenReturn(withSensitive);
        assertThat(SensitiveContentFilter.detectSensitive("我的密码是 abc123")).isNotNull();

        IntentOutcome o = service.handle("s-1", "记住我的密码", 10L);

        assertThat(o.shouldExtract).isTrue(); // 全被过滤，回退聊天
        verify(pendingActionService, never()).create(anyString(), anyString(), anyString(), anyLong());
    }

    @Test
    void shouldFilterInvalidMemoryKey() {
        when(pendingActionService.current("s-1")).thenReturn(null);
        // key 不在 MemoryKey 枚举里 → 丢弃
        Parsed badKey = new Parsed(
                List.of(new MemoryCandidate("tech", "用 Java", "stable", 0.8, null)),
                List.of());
        when(intentExtractor.extract(eq(Intent.MEMORY), anyString())).thenReturn(badKey);

        IntentOutcome o = service.handle("s-1", "记住我用 Java", 11L);

        assertThat(o.shouldExtract).isTrue();
        verify(pendingActionService, never()).create(anyString(), anyString(), anyString(), anyLong());
    }

    @Test
    void shouldSkipNonCreateTaskCandidate() {
        when(pendingActionService.current("s-1")).thenReturn(null);
        Parsed upd = new Parsed(
                List.of(),
                List.of(new TaskCandidate("update", "改标题", null, "Asia/Shanghai", "once", null)));
        when(intentExtractor.extract(eq(Intent.TASK), anyString())).thenReturn(upd);

        IntentOutcome o = service.handle("s-1", "把任务改个名", 12L);

        assertThat(o.shouldExtract).isTrue(); // P1 只允许 create
        verify(pendingActionService, never()).create(anyString(), anyString(), anyString(), anyLong());
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

        assertThat(o.shouldConfirm).isTrue();
        assertThat(o.reply).isNotNull();
        verify(pendingActionService).confirm(99L);
        verify(pendingActionService, never()).reject(anyLong());
    }

    @Test
    void shouldRejectWhenHasPendingAndRejectWord() {
        PendingActionVO pending = new PendingActionVO();
        pending.setId(99L);
        pending.setStatus("pending");
        when(pendingActionService.current("s-1")).thenReturn(pending);

        IntentOutcome o = service.handle("s-1", "取消", 14L);

        assertThat(o.shouldReject).isTrue();
        verify(pendingActionService).reject(99L);
    }

    @Test
    void shouldDescendToChatWhenConfirmWordButNoPending() {
        // 确认词但没 pending（刚过期）→ 不当成确认，回退聊天
        when(pendingActionService.current("s-1")).thenReturn(null);
        IntentOutcome o = service.handle("s-1", "对", 15L);
        // "对" 无 pending 时 detect=NONE → 走聊天
        assertThat(o.shouldExtract).isTrue();
        verify(pendingActionService, never()).confirm(anyLong());
    }
}
