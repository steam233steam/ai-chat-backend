package com.aichat.utils;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
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

    private IntentReplyFormatter() {
    }

    /**
     * 确认前文案：口语化告知用户 AI 准备设置什么（记忆/任务），回复"好"生效、"取消"不保存。
     * 设计原则：像聊天，不像系统通知。保留日期+星期+时间+重复规则（用户才能核对解析对不对）。
     * [[MB:pending=N]] 机器标记由调用方（IntentServiceimpl）用 MbMarkers 拼在尾部。
     */
    public static String buildConfirmReply(List<MemoryCandidate> memories, List<TaskCandidate> tasks) {
        List<String> parts = new ArrayList<>();
        for (MemoryCandidate m : memories) {
            parts.add(memoryPhrase(m));
        }
        for (TaskCandidate t : tasks) {
            parts.add(taskPhrase(t));
        }
        String confirmHint = parts.size() == 1 ? "回复「好」我帮你设上，说「取消」就不存了。"
                : "回复「好」我帮你设好，说「取消」就不存了。";
        return String.join("，", parts) + "，" + confirmHint;
    }

    /** 记忆短语："我记住你的风格是简洁" / "我记住你的作息是晚上十点睡觉" */
    private static String memoryPhrase(MemoryCandidate m) {
        return "我记住你的" + keyLabel(m.getKey()) + "是「" + m.getValue() + "」";
    }

    /** 任务短语："让我提醒你明天 07:00（11-05 周四）起床" / "让我安排每周一交周报" */
    private static String taskPhrase(TaskCandidate t) {
        String when = describeTask(t.getDueAt(), t.getRecurrence(), t.getRecurrenceRule());
        return when + "提醒你「" + t.getTitle() + "」";
    }

    public static String buildConfirmResultReply(ConfirmResult result) {
        List<Map<String, Object>> memories = result.getMemories();
        List<Map<String, Object>> tasks = result.getTasks();
        int n = (memories == null ? 0 : memories.size()) + (tasks == null ? 0 : tasks.size());
        if (n == 0) {
            return "好的，都帮你设上了。";
        }
        StringBuilder sb = new StringBuilder("好的，帮你设好了：");
        List<String> parts = new ArrayList<>();
        if (memories != null) {
            for (Map<String, Object> m : memories) {
                String key = stringValue(m.get("key"));
                String value = stringValue(m.get("value"));
                if (key != null && value != null) {
                    parts.add("记住了" + keyLabel(key) + "是「" + value + "」");
                } else {
                    parts.add("记住了" + "记忆 #" + m.get("id"));
                }
            }
        }
        if (tasks != null) {
            for (Map<String, Object> t : tasks) {
                String title = stringValue(t.get("title"));
                if (title != null) {
                    LocalDateTime dueAt = t.get("dueAt") instanceof LocalDateTime dt ? dt : null;
                    String when = describeTask(dueAt, stringValue(t.get("recurrence")),
                            stringValue(t.get("recurrenceRule")));
                    parts.add(when + "提醒你「" + title + "」");
                } else {
                    parts.add("安排了" + "任务 #" + t.get("id"));
                }
            }
        }
        sb.append(String.join("，", parts)).append("。");
        return sb.toString();
    }

    /** 口语化时间描述："明天 07:00（11-05 周四）" / "每周一" / "无固定时间" */
    private static String describeTask(LocalDateTime dueAt, String recurrence, String rule) {
        if (dueAt == null) {
            return "随时";
        }
        String date = String.format("%02d-%02d", dueAt.getMonthValue(), dueAt.getDayOfMonth());
        String time = String.format("%02d:%02d", dueAt.getHour(), dueAt.getMinute());
        String day = weekLabel(dueAt.getDayOfWeek());
        String rc = recurrenceLabel(recurrence, rule);
        // 单次任务："明天 07:00（11-05 周四）"
        // 重复任务："每周一" 或 "每天"
        if ("单次".equals(rc)) {
            return day + " " + time + "（" + date + "）";
        }
        return rc + "，" + day + " " + time;
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
