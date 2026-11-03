package com.aichat.utils;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * MbMarkers 纯单测：pending 标记的拼接 / 检测 / 提取 / 剥离。
 * 前端弹窗契约 [[MB:pending=N]] 的后端侧保证。
 */
class MbMarkersTest {

    @Test
    void shouldAppendMarkerWithPendingId() {
        String marked = MbMarkers.appendPending("我识别到 1 个任务，确认吗？", 42L);
        assertThat(marked).isEqualTo("我识别到 1 个任务，确认吗？\n[[MB:pending=42]]");
    }

    @Test
    void shouldNotAppendMarkerWhenPendingIdInvalid() {
        assertThat(MbMarkers.appendPending("文案", 0L)).isEqualTo("文案");
        assertThat(MbMarkers.appendPending("文案", -1L)).isEqualTo("文案");
    }

    @Test
    void shouldDetectMarker() {
        assertThat(MbMarkers.hasPendingMarker("abc\n[[MB:pending=7]]")).isTrue();
        assertThat(MbMarkers.hasPendingMarker("普通文案")).isFalse();
        assertThat(MbMarkers.hasPendingMarker(null)).isFalse();
    }

    @Test
    void shouldExtractPendingId() {
        assertThat(MbMarkers.pendingIdOf("前缀[[MB:pending=123]]后缀")).isEqualTo(123L);
        assertThat(MbMarkers.pendingIdOf("普通文案")).isNull();
        assertThat(MbMarkers.pendingIdOf(null)).isNull();
    }

    @Test
    void shouldStripMarkers() {
        String marked = "我识别到 1 个任务，确认吗？\n[[MB:pending=42]]";
        assertThat(MbMarkers.strip(marked)).isEqualTo("我识别到 1 个任务，确认吗？");
        assertThat(MbMarkers.strip(null)).isNull();
        assertThat(MbMarkers.strip("无标记")).isEqualTo("无标记");
    }

    @Test
    void shouldStripMultipleMarkers() {
        String text = "[[MB:pending=1]]中间[[MB:pending=2]]";
        assertThat(MbMarkers.strip(text)).isEqualTo("中间");
    }
}
