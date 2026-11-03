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
        assertThat(IntentDetector.detect("随便聊聊", false)).isEqualTo(Intent.NONE);
        assertThat(IntentDetector.detect("讲个笑话", false)).isEqualTo(Intent.NONE);
        assertThat(IntentDetector.detect("你好呀", false)).isEqualTo(Intent.NONE);
        // 裸"今天"不再触发；只有"今天早上/下午/晚上"等带时段短语才触发
        assertThat(IntentDetector.detect("今天天气怎么样", false)).isEqualTo(Intent.NONE);
    }

    @Test
    void shouldDetectTaskForWeekdayAndTodayTimePhrases() {
        assertThat(IntentDetector.detect("周五下班前把代码提交了", false)).isEqualTo(Intent.TASK);
        assertThat(IntentDetector.detect("下周三 10 点开会", false)).isEqualTo(Intent.TASK);
        assertThat(IntentDetector.detect("今天早上八点吃药", false)).isEqualTo(Intent.TASK);
        assertThat(IntentDetector.detect("每隔一天喝水", false)).isEqualTo(Intent.TASK);
    }

    @Test
    void shouldNotTreatConfirmInsideSentenceAsCommand() {
        assertThat(IntentDetector.detect("确认一下这个方案", false)).isEqualTo(Intent.NONE);
        assertThat(IntentDetector.detect("提前祝你生日快乐", false)).isEqualTo(Intent.NONE);
    }

    @Test
    void shouldReturnNoneForShortWordsWhenNoPending() {
        // "对"/"确认" 在无 pending 时不算意图（避免把普通聊天当确认）
        assertThat(IntentDetector.detect("对", false)).isEqualTo(Intent.NONE);
        assertThat(IntentDetector.detect("好", false)).isEqualTo(Intent.NONE);
        assertThat(IntentDetector.detect("可以", false)).isEqualTo(Intent.NONE);
    }

    @Test
    void shouldNotFalsePositiveOnPraiseWord() {
        // "不错"（称赞）不含明确拒绝短语 → 不触发 REJECT
        assertThat(IntentDetector.detect("不错", true)).isNotEqualTo(Intent.REJECT);
        // 明确的否定短语仍触发 REJECT
        assertThat(IntentDetector.detect("不是啊", true)).isEqualTo(Intent.REJECT);
        assertThat(IntentDetector.detect("不要了", true)).isEqualTo(Intent.REJECT);
    }

    @Test
    void shouldDetectUpdateIntentBeforeTaskCreation() {
        assertThat(IntentDetector.detect("算了延后十分钟吧", false)).isEqualTo(Intent.UPDATE);
        assertThat(IntentDetector.detect("把任务改个名", false)).isEqualTo(Intent.UPDATE);
    }

    @Test
    void shouldDetectNoPendingConfirmWhenNoPending() {
        assertThat(IntentDetector.detect("确认", false)).isEqualTo(Intent.NO_PENDING);
        assertThat(IntentDetector.detect("取消", false)).isEqualTo(Intent.NO_PENDING);
    }

    @Test
    void shouldReturnNoneForBlank() {
        assertThat(IntentDetector.detect(null, true)).isEqualTo(Intent.NONE);
        assertThat(IntentDetector.detect("", false)).isEqualTo(Intent.NONE);
        assertThat(IntentDetector.detect("   ", true)).isEqualTo(Intent.NONE);
    }
}
