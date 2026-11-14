package com.aichat.Service.intent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.aichat.utils.IntentDetector;

/** 兜底判定的纯函数测试：不连模型，保证「该兜的兜、不该兜的不兜」。 */
class IntentRescueTest {

    @Test
    void shouldTreatScheduledQueryAsQueryTask() {
        assertThat(IntentToolEngine.looksLikeScheduledQuery("每天九点查一下北京天气")).isTrue();
        assertThat(IntentToolEngine.looksLikeScheduledQuery("明天早上帮我搜一下LOL赛程")).isTrue();
        assertThat(IntentToolEngine.looksLikeScheduledQuery("现在几点了")).isFalse();
        assertThat(IntentToolEngine.looksLikeScheduledQuery("你好")).isFalse();
    }

    @Test
    void shouldExtractCityAndQuery() {
        assertThat(IntentToolEngine.cityOf("每天九点查一下北京天气")).isEqualTo("北京");
        assertThat(IntentToolEngine.cityOf("明天看看上海天气怎么样")).isEqualTo("上海");
        assertThat(IntentToolEngine.queryOf("明天早上帮我搜一下LOL赛程")).contains("LOL");
    }

    @Test
    void shouldRescueTodo() {
        assertThat(IntentToolEngine.looksLikeTodo("提醒我买牛奶", IntentDetector.Intent.TASK)).isTrue();
        assertThat(IntentToolEngine.looksLikeTodo("今天好累", IntentDetector.Intent.NONE)).isFalse();
        assertThat(IntentToolEngine.rescueTitle("明天早上八点提醒我交作业")).doesNotContain("提醒");
        assertThat(IntentToolEngine.rescueTitle("明天早上八点提醒我交作业")).doesNotContain("明天");
    }

    @Test
    void shouldNotRescueQuestions() {
        assertThat(IntentToolEngine.looksLikeImmediateChat("明天天气怎么样")).isTrue();
        assertThat(IntentToolEngine.looksLikeTodo("明天的天气提醒我一下", IntentDetector.Intent.QUERY)).isFalse();
    }
}
