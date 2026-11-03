package com.aichat.utils;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.aichat.Dto.ConfirmResult;
import com.aichat.Dto.MemoryCandidate;
import com.aichat.Dto.TaskCandidate;

/**
 * 意图确认文案的格式化器（纯函数，不查库、不依赖 Spring）。
 *
 * 从 IntentServiceimpl 拆出，避免编排类里塞满中文文案和日期格式。
 * P1.1：确认文案改自然口吻（"我准备帮你…"），去掉机械的"我识别到：X=Y"格式；
 * 绝对日期+星期+时间+重复规则仍保留（v3 7.2，用户才能发现解析错了）。
 */
public final class IntentReplyFormatter {

    private static final DateTimeFormatter CONFIRM_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private IntentReplyFormatter() {
    }

    /**
     * 确认前文案：让用户知道 AI 准备设置什么（记忆/任务），回复"对"生效、"取消"不保存。
     * [[MB:pending=N]] 机器标记由调用方（IntentServiceimpl）用 MbMarkers 拼在尾部。
     */
    public static String buildConfirmReply(List<MemoryCandidate> memories, List<TaskCandidate> tasks) {
        List<String> parts = new ArrayList<>();
        for (MemoryCandidate m : memories) {
            parts.add("记住「" + keyLabel(m.getKey()) + "=" + m.getValue() + "」");
        }
        for (TaskCandidate t : tasks) {
            parts.add("安排「" + t.getTitle() + "」" + describeTask(t.getDueAt(), t.getRecurrence(),
                    t.getRecurrenceRule()));
        }
        int n = memories.size() + tasks.size();
        return "我准备帮你" + String.join("，并 ", parts)
                + "（共 " + n + " 条）。回复「对」我就帮你设好，回复「取消」则不保存。";
    }

    public static String buildConfirmResultReply(ConfirmResult result) {
        List<Map<String, Object>> memories = result.getMemories();
        List<Map<String, Object>> tasks = result.getTasks();
        int n = (memories == null ? 0 : memories.size()) + (tasks == null ? 0 : tasks.size());
        if (n == 0) {
            return "已确认。";
        }
        StringBuilder sb = new StringBuilder("好的，都帮你设好了（" + n + " 条）：");
        List<String> parts = new ArrayList<>();
        if (memories != null) {
            for (Map<String, Object> m : memories) {
                String key = stringValue(m.get("key"));
                String value = stringValue(m.get("value"));
                if (key != null && value != null) {
                    parts.add("「" + keyLabel(key) + "=" + value + "」");
                } else {
                    parts.add("记忆 #" + m.get("id"));
                }
            }
        }
        if (tasks != null) {
            for (Map<String, Object> t : tasks) {
                String title = stringValue(t.get("title"));
                if (title != null) {
                    LocalDateTime dueAt = t.get("dueAt") instanceof LocalDateTime dt ? dt : null;
                    parts.add("「" + title + "」" + describeTask(
                            dueAt, stringValue(t.get("recurrence")), stringValue(t.get("recurrenceRule"))));
                } else {
                    parts.add("任务 #" + t.get("id"));
                }
            }
        }
        sb.append(String.join("、", parts)).append("。");
        return sb.toString();
    }

    private static String describeTask(LocalDateTime dueAt, String recurrence, String rule) {
        String when = dueAt == null
                ? "（无截止时间）"
                : "（" + CONFIRM_TIME.format(dueAt) + " " + weekLabel(dueAt.getDayOfWeek()) + "，"
                + recurrenceLabel(recurrence, rule) + "）";
        return when;
    }

    private static String recurrenceLabel(String recurrence, String rule) {
        String rc = recurrence == null ? "once" : recurrence;
        return switch (rc) {
            case "daily" -> "每天";
            case "weekly" -> "每周" + byDayLabel(rule);
            default -> "单次";
        };
    }

    private static String byDayLabel(String rule) {
        if (rule == null || !rule.startsWith("BYDAY=")) {
            return "";
        }
        return switch (rule.substring("BYDAY=".length())) {
            case "MO" -> "一";
            case "TU" -> "二";
            case "WE" -> "三";
            case "TH" -> "四";
            case "FR" -> "五";
            case "SA" -> "六";
            case "SU" -> "日";
            default -> "";
        };
    }

    private static String weekLabel(DayOfWeek day) {
        return switch (day) {
            case MONDAY -> "周一";
            case TUESDAY -> "周二";
            case WEDNESDAY -> "周三";
            case THURSDAY -> "周四";
            case FRIDAY -> "周五";
            case SATURDAY -> "周六";
            case SUNDAY -> "周日";
        };
    }

    private static String keyLabel(String key) {
        return switch (key) {
            case "style" -> "风格";
            case "goal" -> "目标";
            case "schedule" -> "作息";
            case "relation" -> "称呼/关系";
            default -> key;
        };
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
