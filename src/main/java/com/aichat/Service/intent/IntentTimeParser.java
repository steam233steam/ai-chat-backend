package com.aichat.Service.intent;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 「明天九点」「后天下午三点半」这类相对时间的确定性解析。
 *
 * <p>为什么不用模型：模型偶尔会漏填 dueAt（schema 里它可空），一旦漏了，
 * 草案就变成「没有时间的待办」，用户还得再说一遍。这里是纯规则兜底，
 * 只在模型没填时间时补一刀，不覆盖模型给出的结果。
 *
 * <p>中文数字（九点、十一点、二十点）必须支持——用户日常就是这么说。
 */
final class IntentTimeParser {

    private static final String NUM = "[0-9]{1,2}|[一二三四五六七八九十]{1,3}";

    private static final Pattern TIME = Pattern.compile(
            "(今天|明天|后天|大后天|今晚)?\\s*(早上|上午|中午|下午|傍晚|晚上|夜里)?\\s*(" + NUM
                    + ")\\s*[点:：]\\s*(" + NUM + "|半)?\\s*分?");

    private static final String DIGITS = "一二三四五六七八九";

    private IntentTimeParser() {
    }

    /** 解析不出来返回 null（调用方据此保留「未设置时间」）。 */
    static LocalDateTime parse(String message, LocalDateTime now) {
        if (message == null || message.isBlank()) {
            return null;
        }
        Matcher m = TIME.matcher(message);
        if (!m.find()) {
            return null;
        }
        Integer hour = number(m.group(3));
        if (hour == null) {
            return null;
        }
        int minute = 0;
        String minuteText = m.group(4);
        if (minuteText != null && !minuteText.isBlank()) {
            if ("半".equals(minuteText)) {
                minute = 30;
            } else {
                Integer minutes = number(minuteText);
                if (minutes == null) {
                    return null;
                }
                minute = minutes;
            }
        }
        if (hour > 23 || minute > 59) {
            return null;
        }
        String dayText = m.group(1);
        String period = m.group(2);
        if (period != null) {
            boolean afternoon = period.equals("下午") || period.equals("傍晚") || period.equals("晚上")
                    || period.equals("夜里");
            if (period.equals("中午")) {
                hour = hour < 11 ? hour + 12 : hour;
            } else if (afternoon && hour < 12) {
                hour += 12;
            }
        } else if ("今晚".equals(dayText) && hour < 12) {
            // 「今晚八点」：晚上在后面那个词里，不单独成组
            hour += 12;
        }
        LocalDate date = now.toLocalDate();
        if (dayText != null) {
            switch (dayText) {
                case "明天" -> date = date.plusDays(1);
                case "后天" -> date = date.plusDays(2);
                case "大后天" -> date = date.plusDays(3);
                default -> {
                    // 今天 / 今晚：仍用今天
                }
            }
        }
        LocalDateTime at = LocalDateTime.of(date, LocalTime.of(hour, minute));
        // 「今天九点」但已经过了 → 顺延到明天，符合直觉
        if (!at.isAfter(now)) {
            at = at.plusDays(1);
        }
        return at;
    }

    /** 支持 9 / 九 / 十一 / 二十 / 二十三。 */
    static Integer number(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String t = text.trim();
        if (t.matches("\\d{1,2}")) {
            return Integer.parseInt(t);
        }
        int ten = t.indexOf('十');
        if (ten >= 0) {
            int tens = ten == 0 ? 1 : DIGITS.indexOf(t.charAt(0)) + 1;
            int ones = ten == t.length() - 1 ? 0 : DIGITS.indexOf(t.charAt(t.length() - 1)) + 1;
            if (tens <= 0 || ones < 0) {
                return null;
            }
            return tens * 10 + ones;
        }
        if (t.length() == 1) {
            int idx = DIGITS.indexOf(t.charAt(0));
            return idx < 0 ? null : idx + 1;
        }
        return null;
    }
}
