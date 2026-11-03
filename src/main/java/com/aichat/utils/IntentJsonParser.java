package com.aichat.utils;

import java.util.ArrayList;
import java.util.List;

import com.aichat.Dto.MemoryCandidate;
import com.aichat.Dto.TaskCandidate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 解析意图提取器 LLM 输出的结构化 JSON，转成 MemoryCandidate / TaskCandidate。
 *
 * LLM 约定输出（见 IntentPrompts）：
 * {
 *   "memories": [ { "key":"style","value":"简洁","memType":"stable","confidence":0.9,"replacesId":null } ],
 *   "tasks":    [ { "action":"create","title":"交作业","dueAt":"2026-11-03T09:00:00","recurrence":"once" } ]
 * }
 *
 * 解析策略（失败降级，v3 9.5）：
 * - 顶层不是对象 / 缺字段 → 空列表（交给 IntentService 走兜底文案）
 * - 单条候选 key/value/title 缺失 → 跳过该条，不阻断其它条
 * - 因 LLM 偶尔会输出 ```json 围栏或前后缀，先剥离再解析
 * - 全程不抛异常；解析失败一律按空处理并打 warn
 */
public final class IntentJsonParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 解析结果：memories + tasks（都可能是空列表）。 */
    public static class Parsed {
        public final List<MemoryCandidate> memories;
        public final List<TaskCandidate> tasks;

        public Parsed(List<MemoryCandidate> memories, List<TaskCandidate> tasks) {
            this.memories = memories;
            this.tasks = tasks;
        }

        public boolean isEmpty() {
            return memories.isEmpty() && tasks.isEmpty();
        }
    }

    private IntentJsonParser() {
    }

    public static Parsed parse(String raw) {
        List<MemoryCandidate> memories = new ArrayList<>();
        List<TaskCandidate> tasks = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return new Parsed(memories, tasks);
        }
        try {
            JsonNode root = MAPPER.readTree(stripFence(raw));
            parseMemories(root, memories);
            parseTasks(root, tasks);
        } catch (Exception e) {
            // LLM 输出不稳定：解析失败按"什么都没提取到"处理，不抛异常阻断聊天
        }
        return new Parsed(memories, tasks);
    }

    private static void parseMemories(JsonNode root, List<MemoryCandidate> out) {
        JsonNode arr = root.get("memories");
        if (arr == null || !arr.isArray()) {
            return;
        }
        for (JsonNode node : arr) {
            String key = textOrNull(node.get("key"));
            String value = textOrNull(node.get("value"));
            if (key == null || value == null) {
                continue; // 缺关键字段，跳过这条
            }
            MemoryCandidate c = new MemoryCandidate();
            c.setKey(key.trim());
            c.setValue(value.trim());
            c.setMemType(textOrNull(node.get("memType")));
            c.setConfidence(doubleOrNull(node.get("confidence")));
            c.setReplacesId(longOrNull(node.get("replacesId")));
            out.add(c);
        }
    }

    private static void parseTasks(JsonNode root, List<TaskCandidate> out) {
        JsonNode arr = root.get("tasks");
        if (arr == null || !arr.isArray()) {
            return;
        }
        for (JsonNode node : arr) {
            String title = textOrNull(node.get("title"));
            if (title == null) {
                continue;
            }
            TaskCandidate c = new TaskCandidate();
            c.setAction(textOrNull(node.get("action")));
            c.setTitle(title.trim());
            c.setDueAt(dateTimeOrNull(node.get("dueAt")));
            c.setTimezone(textOrNull(node.get("timezone")));
            c.setRecurrence(textOrNull(node.get("recurrence")));
            c.setRecurrenceRule(textOrNull(node.get("recurrenceRule")));
            out.add(c);
        }
    }

    // ---------- 字段抽取工具 ----------

    private static String textOrNull(JsonNode n) {
        if (n == null || n.isNull() || n.isMissingNode()) {
            return null;
        }
        String s = n.asText();
        return s.isBlank() ? null : s;
    }

    private static Double doubleOrNull(JsonNode n) {
        if (n == null || n.isNull() || n.isMissingNode()) {
            return null;
        }
        try {
            return n.asDouble();
        } catch (Exception e) {
            return null;
        }
    }

    private static Long longOrNull(JsonNode n) {
        if (n == null || n.isNull() || n.isMissingNode()) {
            return null;
        }
        try {
            return n.asLong();
        } catch (Exception e) {
            return null;
        }
    }

    /** dueAt 支持 "2026-11-03T09:00:00"（ISO T 分隔）或 "2026-11-03 09:00:00"（空格分隔，LLM 常见变体）。 */
    private static java.time.LocalDateTime dateTimeOrNull(JsonNode n) {
        String s = textOrNull(n);
        if (s == null) {
            return null;
        }
        // 先试 ISO 标准（T 分隔），再试空格分隔变体；都失败当没给截止时间
        for (String candidate : new String[] { s.trim(), s.trim().replace(' ', 'T') }) {
            try {
                return java.time.LocalDateTime.parse(candidate);
            } catch (Exception ignore) {
                // try next
            }
        }
        return null;
    }

    /** 剥掉 LLM 可能输出的 ```json ... ``` 围栏，只留中间 JSON。 */
    private static String stripFence(String raw) {
        String s = raw.trim();
        // 去掉开头的 ```json / ```（任意后缀）
        if (s.startsWith("```")) {
            int firstNewline = s.indexOf('\n');
            if (firstNewline > 0) {
                s = s.substring(firstNewline + 1);
            } else {
                s = s.substring(3);
            }
        }
        // 去掉结尾的 ```
        int end = s.lastIndexOf("```");
        if (end >= 0) {
            s = s.substring(0, end);
        }
        return s.trim();
    }
}
