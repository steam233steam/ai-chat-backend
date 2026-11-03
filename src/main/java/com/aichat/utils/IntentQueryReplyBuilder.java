package com.aichat.utils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import com.aichat.Dto.MemoryVO;
import com.aichat.Dto.TaskVO;

/**
 * 意图查询类（QUERY）文案的格式化器：把已有的任务/记忆数据读出来拼成自然回复。
 * 纯函数，不查库、不依赖 Spring（数据由 IntentService 查好传进来）。
 */
public final class IntentQueryReplyBuilder {

    private static final DateTimeFormatter QUERY_TIME =
            DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private IntentQueryReplyBuilder() {
    }

    /**
     * 拼"我的任务"列表回复。
     *
     * @param pending 未完成任务（最多 limit 条）
     * @param done    已完成任务（最多 limit 条）
     * @param due     已到期任务（最多 limit 条，可能和 pending 重复，只标不重列）
     * @param hasMore 是否还有更多（用于提示"只看最近 N 条"）
     */
    public static String buildTaskList(List<TaskVO> pending, List<TaskVO> done,
            List<TaskVO> due, int showLimit) {
        if (pending.isEmpty() && done.isEmpty()) {
            return "你目前还没有任何任务。想加一条，直接说就行，比如“明天 9 点提醒我交作业”。";
        }
        StringBuilder sb = new StringBuilder();
        List<String> dueTitles = due.stream().map(TaskVO::getTitle).toList();
        if (!pending.isEmpty()) {
            sb.append("你还没完成的任务（共 ").append(pending.size()).append(" 条）：\n");
            int shown = Math.min(pending.size(), showLimit);
            for (int i = 0; i < shown; i++) {
                sb.append(taskLine(pending.get(i), dueTitles));
            }
            if (pending.size() > shown) {
                sb.append("…还有 ").append(pending.size() - shown).append(" 条，可以在任务列表里翻看。\n");
            }
        }
        if (!done.isEmpty()) {
            sb.append("已完成的任务（最近 ").append(Math.min(done.size(), showLimit)).append(" 条）：\n");
            for (int i = 0; i < Math.min(done.size(), showLimit); i++) {
                sb.append(taskLine(done.get(i), List.of()));
            }
        }
        sb.append("\n要完成哪条，直接说任务名就行（P1 暂不支持聊天里删改任务）。");
        return sb.toString();
    }

    /** 拼"你记住了我什么"列表回复。 */
    public static String buildMemoryList(List<MemoryVO> memories, int showLimit) {
        if (memories.isEmpty()) {
            return "我目前还没记住关于你的信息。想让我记住，直接说就行，比如“记住我一般晚上十点睡觉”。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("我记住你的 ").append(memories.size()).append(" 条信息：\n");
        int shown = Math.min(memories.size(), showLimit);
        for (int i = 0; i < shown; i++) {
            MemoryVO m = memories.get(i);
            sb.append("· ").append(m.getMemKey()).append("：").append(m.getContent());
            if ("dynamic".equals(m.getMemType()) && m.getExpiresAt() != null) {
                sb.append("（").append(QUERY_TIME.format(m.getExpiresAt())).append(" 前有效）");
            }
            sb.append("\n");
        }
        if (memories.size() > shown) {
            sb.append("…还有 ").append(memories.size() - shown).append(" 条，可以在记忆列表里翻看。\n");
        }
        return sb.toString();
    }

    private static String taskLine(TaskVO t, List<String> dueTitles) {
        String line = "· " + t.getTitle();
        if (t.getDueAt() != null) {
            line += "（" + QUERY_TIME.format(t.getDueAt()) + "，" + recurrenceLabel(t.getRecurrence()) + "）";
        }
        if (dueTitles.contains(t.getTitle())) {
            line += " ⏰ 已到期";
        }
        if ("done".equals(t.getStatus())) {
            line += " ✅";
        }
        return line + "\n";
    }

    private static String recurrenceLabel(String recurrence) {
        return switch (recurrence == null ? "once" : recurrence) {
            case "daily" -> "每天";
            case "weekly" -> "每周";
            default -> "单次";
        };
    }
}
