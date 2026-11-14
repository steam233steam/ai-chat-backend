package com.aichat.Service.intent;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

/** 相对时间兜底解析：只在模型没填 dueAt 时用，规则要稳、可解释。 */
class IntentTimeParserTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 14, 19, 0);

    @Test
    void shouldParseTomorrowHour() {
        assertThat(IntentTimeParser.parse("明天九点提醒我交作业", NOW))
                .isEqualTo(LocalDateTime.of(2026, 11, 15, 9, 0));
    }

    @Test
    void shouldParseAfternoonHalfPast() {
        assertThat(IntentTimeParser.parse("后天下午三点半开会", NOW))
                .isEqualTo(LocalDateTime.of(2026, 11, 16, 15, 30));
    }

    @Test
    void shouldParseTonight() {
        assertThat(IntentTimeParser.parse("今晚八点给我推一条新闻", NOW))
                .isEqualTo(LocalDateTime.of(2026, 11, 14, 20, 0));
    }

    @Test
    void shouldRollToTomorrowWhenTodayAlreadyPast() {
        LocalDateTime noon = LocalDateTime.of(2026, 11, 14, 12, 0);
        assertThat(IntentTimeParser.parse("今天九点提醒我", noon))
                .isEqualTo(LocalDateTime.of(2026, 11, 15, 9, 0));
    }

    @Test
    void shouldParseNoonAsTwelve() {
        assertThat(IntentTimeParser.parse("明天中午十二点吃饭", NOW))
                .isEqualTo(LocalDateTime.of(2026, 11, 15, 12, 0));
    }

    @Test
    void shouldReturnNullWhenNoTimeFound() {
        assertThat(IntentTimeParser.parse("提醒我买牛奶", NOW)).isNull();
        assertThat(IntentTimeParser.parse("", NOW)).isNull();
        assertThat(IntentTimeParser.parse(null, NOW)).isNull();
    }
}
