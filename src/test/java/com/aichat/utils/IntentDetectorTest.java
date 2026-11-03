package com.aichat.utils;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.aichat.utils.IntentDetector.Intent;

/**
 * IntentDetector 纯静态单测：触发词粗筛的分支覆盖（不调 LLM、不注入 Spring）。
 */
class IntentDetectorTest {

    @Test
    void shouldDetectMemoryWhenNoPending() {
        assertThat(IntentDetector.detect("记住我喜欢简洁", false)).isEqualTo(Intent.MEMORY);
        assertThat(IntentDetector.detect("帮我记住我一般晚上十点睡觉", false)).isEqualTo(Intent.MEMORY);
        assertThat(IntentDetector.detect("我的习惯是先喝咖啡", false)).isEqualTo(Intent.MEMORY);
    }

    @Test
    void shouldDetectTaskWhenNoPending() {
        assertThat(IntentDetector.detect("明天 9 点交作业", false)).isEqualTo(Intent.TASK);
        assertThat(IntentDetector.detect("每周一交报告", false)).isEqualTo(Intent.TASK);
        assertThat(IntentDetector.detect("提醒我下周五提交方案", false)).isEqualTo(Intent.TASK);
        // 任务词优先于记忆词（"明天要交"是任务）
        assertThat(IntentDetector.detect("记住我明天要交作业", false)).isEqualTo(Intent.TASK);
    }

    @Test
    void shouldDetectConfirmWhenHasPending() {
        assertThat(IntentDetector.detect("对", true)).isEqualTo(Intent.CONFIRM);
        assertThat(IntentDetector.detect("确认", true)).isEqualTo(Intent.CONFIRM);
        assertThat(IntentDetector.detect("好的", true)).isEqualTo(Intent.CONFIRM);
        assertThat(IntentDetector.detect("可以", true)).isEqualTo(Intent.CONFIRM);
        assertThat(IntentDetector.detect("ok", true)).isEqualTo(Intent.CONFIRM);
    }

    @Test
    void shouldDetectRejectWhenHasPending() {
        assertThat(IntentDetector.detect("不对", true)).isEqualTo(Intent.REJECT);
        assertThat(IntentDetector.detect("取消", true)).isEqualTo(Intent.REJECT);
        assertThat(IntentDetector.detect("不用了", true)).isEqualTo(Intent.REJECT);
        // 拒绝词优先于确认词
        assertThat(IntentDetector.detect("不对，取消", true)).isEqualTo(Intent.REJECT);
    }

    @Test
    void shouldReturnNoneWhenNoPendingAndNoTrigger() {
        // 粗筛"宽进"：日期词（今天/明天）会误触发 TASK，靠提取器空候选降级兜底（见 IntentServiceimplTest）
        assertThat(IntentDetector.detect("随便聊聊", false)).isEqualTo(Intent.NONE);
        assertThat(IntentDetector.detect("讲个笑话", false)).isEqualTo(Intent.NONE);
        assertThat(IntentDetector.detect("你好呀", false)).isEqualTo(Intent.NONE);
    }

    @Test
    void shouldReturnNoneForShortWordsWhenNoPending() {
        // "对"/"确认" 在无 pending 时不算意图（避免把普通聊天当确认）
        assertThat(IntentDetector.detect("对", false)).isEqualTo(Intent.NONE);
        assertThat(IntentDetector.detect("好", false)).isEqualTo(Intent.NONE);
        assertThat(IntentDetector.detect("可以", false)).isEqualTo(Intent.NONE);
    }

    @Test
    void shouldReturnNoneForBlank() {
        assertThat(IntentDetector.detect(null, true)).isEqualTo(Intent.NONE);
        assertThat(IntentDetector.detect("", false)).isEqualTo(Intent.NONE);
        assertThat(IntentDetector.detect("   ", true)).isEqualTo(Intent.NONE);
    }
}
