package com.aichat.Service.impl;

import java.time.DayOfWeek;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.aichat.Config.MindButlerProperties;
import com.aichat.Dto.ConfirmResult;
import com.aichat.Dto.MemoryCandidate;
import com.aichat.Dto.PendingActionVO;
import com.aichat.Dto.TaskCandidate;
import com.aichat.Dto.UserDto;
import com.aichat.Exception.BusinessException;
import com.aichat.Service.IntentDailyLimiter;
import com.aichat.Service.IntentExtractor;
import com.aichat.Service.IntentOutcome;
import com.aichat.Service.IntentService;
import com.aichat.Service.PendingActionService;
import com.aichat.utils.IntentDetector;
import com.aichat.utils.IntentDetector.Intent;
import com.aichat.utils.IntentJsonParser;
import com.aichat.utils.IntentJsonParser.Parsed;
import com.aichat.utils.MemoryKey;
import com.aichat.utils.SensitiveContentFilter;
import com.aichat.utils.UserHolder;

/**
 * 意图服务实现：粗筛 → 提取 → 校验 → 写 pending → 出确认文案。
 *
 * 关键点（v3 4.4/4.5/9.5）：
 * - 确认/拒绝优先：有 pending 时，确认/拒绝词直接走 PendingActionService，不跑 LLM；
 * - 提取前旧 pending 自动 expired（PendingActionService.create 内部 expireSession）；
 * - 提取器失败/空候选 → 兜底文案，不 500、不污染聊天上下文；
 * - 候选校验：敏感词拒绝、memKey 合法性过滤、任务非 create 动作跳过（P1）。
 */
@Service
public class IntentServiceimpl implements IntentService {
    private static final Logger log = LoggerFactory.getLogger(IntentServiceimpl.class);

    private static final DateTimeFormatter CONFIRM_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private static final String EXTRACT_FALLBACK =
            "我没识别出要保存的记忆或任务。可以再说具体一点，比如“记住我喜欢简洁”或“明天 9 点提醒我交作业”。";
    private static final String CONFIRM_EXPIRED_FALLBACK =
            "这条确认已过期或已失效，请重新说一遍。";

    @Autowired
    private PendingActionService pendingActionService;
    @Autowired
    private IntentExtractor intentExtractor;
    @Autowired
    private MindButlerProperties props;
    @Autowired
    private IntentDailyLimiter intentDailyLimiter;

    @Override
    public IntentOutcome handle(String sessionId, String message, Long sourceMessageId) {
        // 总开关/意图开关关闭：只保留手动 CRUD，不做意图提取/确认
        if (!props.isEnabled() || !props.isIntentEnabled()) {
            return IntentOutcome.chat();
        }

        PendingActionVO pending = pendingActionService.current(sessionId);
        boolean hasPending = pending != null;
        Intent intent = IntentDetector.detect(message, hasPending);

        switch (intent) {
            case NONE:
                return IntentOutcome.chat();
            case CONFIRM:
                return confirm(pending, message, sourceMessageId);
            case REJECT:
                return reject(pending);
            case MEMORY:
            case TASK:
            default:
                return extract(intent, sessionId, message, sourceMessageId);
        }
    }

    // ---------- 提取 → 校验 → 写 pending → 确认文案 ----------

    private IntentOutcome extract(Intent intent, String sessionId, String message, Long sourceMessageId) {
        String userId = currentUserId();
        if (!intentDailyLimiter.tryAcquire(userId)) {
            return IntentOutcome.replyOnly("今天的意图识别次数已用完，明天再试。");
        }
        Parsed parsed = intentExtractor.extract(intent, message);
        if (parsed == null || parsed.isEmpty()) {
            return IntentOutcome.replyOnly(EXTRACT_FALLBACK); // 设计 8.4：固定兜底，不调聊天模型
        }

        // 校验：过滤敏感内容 + 非法 memKey + 非 create 任务
        List<MemoryCandidate> memories = sanitizeMemories(parsed.memories);
        List<TaskCandidate> tasks = sanitizeTasks(parsed.tasks);
        if (memories.isEmpty() && tasks.isEmpty()) {
            return IntentOutcome.replyOnly(EXTRACT_FALLBACK); // 全部被过滤掉，也给固定兜底
        }

        // 写 pending（旧 pending 自动 expired；action_type 按实际内容分 memory/task/mixed）
        String actionType = (memories.isEmpty() ? "task" : tasks.isEmpty() ? "memory" : "mixed");
        String payload = buildPayload(memories, tasks, message);
        long pendingId;
        try {
            pendingId = pendingActionService.create(sessionId, actionType, payload, sourceMessageId);
        } catch (BusinessException e) {
            // 并发 pending_slot 冲突 / payload 超限：不要让 ChatService 回退去调聊天模型
            log.warn("写 pending 失败（{}）: session={}", e.getMessage(), sessionId);
            return IntentOutcome.replyOnly("刚才的提案发生冲突，请再试一次。");
        }

        // 出确认文案（复用 PendingActionVO 的 preview 风格）
        String reply = buildConfirmReply(pendingId, memories, tasks);
        log.info("intent 提取: session={}, pendingId={}, memories={}, tasks={}",
                sessionId, pendingId, memories.size(), tasks.size());
        return IntentOutcome.extracted(reply);
    }

    // ---------- 确认 / 拒绝 ----------

    private IntentOutcome confirm(PendingActionVO pending, String message, Long sourceMessageId) {
        if (pending == null) {
            // 有确认词但没 pending（比如 pending 刚过期）→ 回退正常聊天
            return IntentOutcome.chat();
        }
        ConfirmResult cr;
        try {
            cr = pendingActionService.confirm(pending.getId());
        } catch (BusinessException e) {
            // 409 已拒绝/已过期：固定说明，不调聊天模型
            log.info("confirm 失败（{}）: pendingId={}", e.getMessage(), pending.getId());
            return IntentOutcome.replyOnly(CONFIRM_EXPIRED_FALLBACK);
        }
        if (cr.isAlreadyConfirmed()) {
            return IntentOutcome.confirmed(cr, "刚才已经确认过了，没有重复创建。");
        }
        String reply = buildConfirmResultReply(cr.getMemories(), cr.getTasks());
        return IntentOutcome.confirmed(cr, reply);
    }

    private IntentOutcome reject(PendingActionVO pending) {
        if (pending == null) {
            return IntentOutcome.chat();
        }
        try {
            pendingActionService.reject(pending.getId());
        } catch (BusinessException e) {
            log.info("reject 失败（{}）: pendingId={}", e.getMessage(), pending.getId());
            return IntentOutcome.replyOnly(CONFIRM_EXPIRED_FALLBACK);
        }
        return IntentOutcome.rejected("好的，已取消这条待确认。");
    }

    // ---------- 候选净化 ----------

    private List<MemoryCandidate> sanitizeMemories(List<MemoryCandidate> in) {
        List<MemoryCandidate> out = new java.util.ArrayList<>();
        if (in == null) {
            return out;
        }
        int max = Math.max(0, props.getIntentMaxMemories());
        for (MemoryCandidate c : in) {
            if (out.size() >= max) {
                break; // 超出单条消息上限，截断
            }
            if (c == null || c.getKey() == null) {
                continue;
            }
            String key = c.getKey().trim().toLowerCase(Locale.ROOT);
            if (!MemoryKey.isValid(key)) {
                continue; // 非法/缺 key 的候选丢弃
            }
            String value = SensitiveContentFilter.stripControlChars(c.getValue());
            if (value.isEmpty()) {
                continue;
            }
            int len = value.codePointCount(0, value.length());
            if (len > 256) {
                value = value.substring(0, value.offsetByCodePoints(0, 256));
            }
            String sensitive = SensitiveContentFilter.detectSensitive(value);
            if (sensitive != null) {
                log.warn("跳过含{}的记忆候选: key={}", sensitive, key);
                continue;
            }
            String memType = (c.getMemType() == null || c.getMemType().isBlank())
                    ? "stable" : c.getMemType().trim().toLowerCase(Locale.ROOT);
            if (!"stable".equals(memType) && !"dynamic".equals(memType)) {
                continue; // 非法 memType 丢弃，避免确认时才 400
            }
            c.setKey(key);
            c.setValue(value);
            c.setMemType(memType);
            if (c.getConfidence() != null) {
                c.setConfidence(Math.max(0.0, Math.min(1.0, c.getConfidence())));
            }
            out.add(c);
        }
        return out;
    }

    private List<TaskCandidate> sanitizeTasks(List<TaskCandidate> in) {
        List<TaskCandidate> out = new java.util.ArrayList<>();
        if (in == null) {
            return out;
        }
        int max = Math.max(0, props.getIntentMaxTasks());
        for (TaskCandidate c : in) {
            if (out.size() >= max) {
                break; // 超出单条消息上限，截断
            }
            if (c == null) {
                continue;
            }
            String title = SensitiveContentFilter.stripControlChars(c.getTitle());
            if (title.isEmpty()) {
                continue;
            }
            int len = title.codePointCount(0, title.length());
            if (len > 128) {
                title = title.substring(0, title.offsetByCodePoints(0, 128));
            }
            // P1 只允许 create（update/delete 留 P1.5）
            String action = (c.getAction() == null || c.getAction().isBlank())
                    ? "create" : c.getAction().trim().toLowerCase(Locale.ROOT);
            if (!"create".equals(action)) {
                continue;
            }
            String recurrence = (c.getRecurrence() == null || c.getRecurrence().isBlank())
                    ? "once" : c.getRecurrence().trim().toLowerCase(Locale.ROOT);
            if (!"once".equals(recurrence) && !"daily".equals(recurrence) && !"weekly".equals(recurrence)) {
                continue;
            }
            String rule = null;
            if ("weekly".equals(recurrence)) {
                if (c.getRecurrenceRule() == null) {
                    continue; // 缺 BYDAY 的候选不要写进 pending，否则确认时才 400
                }
                rule = c.getRecurrenceRule().trim().toUpperCase(Locale.ROOT);
                if (!rule.matches("^BYDAY=(MO|TU|WE|TH|FR|SA|SU)$")) {
                    continue;
                }
            }
            if (!"once".equals(recurrence) && c.getDueAt() == null) {
                continue; // daily/weekly 必须有截止时间
            }
            String timezone = (c.getTimezone() == null || c.getTimezone().isBlank())
                    ? "Asia/Shanghai" : c.getTimezone().trim();
            if (!"Asia/Shanghai".equals(timezone)) {
                continue; // P1 固定单时区
            }
            c.setAction("create");
            c.setTitle(title);
            c.setRecurrence(recurrence);
            c.setRecurrenceRule(rule);
            c.setTimezone(timezone);
            out.add(c);
        }
        return out;
    }

    // ---------- 文案 / payload ----------

    private String buildPayload(List<MemoryCandidate> memories, List<TaskCandidate> tasks, String rawInput) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper om =
                    new com.fasterxml.jackson.databind.ObjectMapper()
                            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
            java.util.Map<String, Object> root = new java.util.LinkedHashMap<>();
            root.put("memories", memories);
            root.put("tasks", tasks);
            root.put("rawInput", rawInput);
            return om.writeValueAsString(root);
        } catch (Exception e) {
            throw new BusinessException("生成待确认内容失败，请重新说一遍");
        }
    }

    private String buildConfirmReply(long pendingId, List<MemoryCandidate> memories, List<TaskCandidate> tasks) {
        StringBuilder sb = new StringBuilder("我识别到：");
        List<String> parts = new java.util.ArrayList<>();
        for (MemoryCandidate m : memories) {
            parts.add("记忆「" + keyLabel(m.getKey()) + "=" + m.getValue() + "」");
        }
        for (TaskCandidate t : tasks) {
            // 必须显示绝对日期 + 星期 + 时间 + 重复规则，用户才能发现"下周三"解析错了（v3 7.2）
            parts.add("任务「" + t.getTitle() + "」" + describeTask(t));
        }
        sb.append(String.join("、", parts)).append("。确认吗？（回复 对/确认 生效，取消则不保存）");
        return sb.toString();
    }

    private String describeTask(TaskCandidate t) {
        return describeTask(t.getDueAt(), t.getRecurrence(), t.getRecurrenceRule());
    }

    private String describeTask(java.time.LocalDateTime dueAt, String recurrence, String rule) {
        String when = dueAt == null
                ? "无截止时间"
                : CONFIRM_TIME.format(dueAt) + "（" + weekLabel(dueAt.getDayOfWeek()) + "）";
        return "：" + when + "，" + recurrenceLabel(recurrence, rule);
    }

    private String recurrenceLabel(String recurrence, String rule) {
        String rc = recurrence == null ? "once" : recurrence;
        return switch (rc) {
            case "daily" -> "每天";
            case "weekly" -> "每周" + byDayLabel(rule);
            default -> "单次";
        };
    }

    private String byDayLabel(String rule) {
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

    private String weekLabel(DayOfWeek day) {
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

    private String keyLabel(String key) {
        return switch (key) {
            case "style" -> "风格";
            case "goal" -> "目标";
            case "schedule" -> "作息";
            case "relation" -> "称呼/关系";
            default -> key;
        };
    }

    private String buildConfirmResultReply(
            List<java.util.Map<String, Object>> memories, List<java.util.Map<String, Object>> tasks) {
        int n = (memories == null ? 0 : memories.size()) + (tasks == null ? 0 : tasks.size());
        if (n == 0) {
            return "已确认。";
        }
        StringBuilder sb = new StringBuilder("已为你保存 " + n + " 条：");
        List<String> parts = new java.util.ArrayList<>();
        if (memories != null) {
            for (java.util.Map<String, Object> m : memories) {
                String key = stringValue(m.get("key"));
                String value = stringValue(m.get("value"));
                if (key != null && value != null) {
                    parts.add("记忆「" + keyLabel(key) + "=" + value + "」");
                } else {
                    parts.add("记忆 #" + m.get("id"));
                }
            }
        }
        if (tasks != null) {
            for (java.util.Map<String, Object> t : tasks) {
                String title = stringValue(t.get("title"));
                if (title != null) {
                    java.time.LocalDateTime dueAt = t.get("dueAt") instanceof java.time.LocalDateTime dt
                            ? dt : null;
                    parts.add("任务「" + title + "」" + describeTask(
                            dueAt, stringValue(t.get("recurrence")), stringValue(t.get("recurrenceRule"))));
                } else {
                    parts.add("任务 #" + t.get("id"));
                }
            }
        }
        sb.append(String.join("、", parts)).append("。");
        return sb.toString();
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String currentUserId() {
        UserDto user = UserHolder.getUser();
        if (user == null || user.getId() == null) {
            throw new BusinessException(401, "请先登录");
        }
        return user.getId();
    }
}
