package com.aichat.Service.intent;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.aichat.Dto.MemoryCandidate;
import com.aichat.Dto.TaskCandidate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 意图抽取可用的工具清单。
 *
 * <p>这里只放「说话就能干」的内置动作：记一条、建一个任务。MCP 里那些查询工具
 * （天气、搜索、网页正文）由 {@code McpToolCatalog} 另外提供，两边拼在一起交给模型。
 * 新增 MCP 服务器时这里一行都不用改——这正是这次改造要解决的问题。
 */
@Component
public class IntentToolCatalog {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private static final String CREATE_TASK_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "title": {"type": "string", "description": "任务标题，动宾短语，不要包含时间，例如「交作业」"},
                "dueAt": {"type": "string", "description": "首次触发时间，ISO-8601 本地时间，例如 2026-11-11T09:00:00；拿不准就用今天之后最近的合理时间"},
                "recurrence": {"type": "string", "enum": ["once", "daily", "weekly"], "description": "一次性/每天/每周"},
                "recurrenceRule": {"type": "string", "description": "weekly 时填 BYDAY=MO 这种格式，其余留空"},
                "action": {"type": "string", "enum": ["notify", "mcp", "push"], "description": "到点做什么：站内提醒/调 MCP 工具查一次/推到手机"},
                "tool": {"type": "string", "description": "action=mcp 时填要查的工具短名，例如 weather、search、now"},
                "toolArgs": {"type": "object", "description": "action=mcp 时该工具的参数，键值对形式，例如 city=北京"}
              },
              "required": ["title", "dueAt", "recurrence"]
            }
            """;

    private static final String REMEMBER_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "key": {"type": "string", "enum": ["style", "fact", "relation"], "description": "style=说话风格偏好；fact=关于用户的陈述性事实（目标、打算、正在准备的事）；relation=称呼与人物关系"},
                "value": {"type": "string", "description": "要记住的内容，一句话，第二人称视角，例如「喜欢简洁的回答」"},
                "memType": {"type": "string", "enum": ["stable", "dynamic"], "description": "长期不变选 stable，阶段性选 dynamic"},
                "confidence": {"type": "number", "description": "0~1，模型对这条记忆的把握"}
              },
              "required": ["key", "value"]
            }
            """;

    /** 内置动作 + 状态收集器 → 本次请求可用的工具列表 */
    public List<org.springframework.ai.model.function.FunctionCallback> build(IntentPlan plan) {
        List<org.springframework.ai.model.function.FunctionCallback> tools = new ArrayList<>();
        tools.add(new IntentToolCallback("create_task",
                "用户要求在某时间做某事、或要求定期做某事时调用。带时间安排的一律走这里，不要写进 remember。",
                CREATE_TASK_SCHEMA, args -> createTask(plan, args)));
        tools.add(new IntentToolCallback("remember",
                "用户要求记住关于他自己的信息（偏好、风格、称呼关系、目标打算）时调用。",
                REMEMBER_SCHEMA, args -> remember(plan, args)));
        return tools;
    }

    private String createTask(IntentPlan plan, String args) {
        JsonNode node = read(args);
        String title = text(node, "title");
        if (title.isBlank()) {
            throw new IllegalArgumentException("title 不能为空");
        }
        LocalDateTime dueAt = parseDue(text(node, "dueAt"));
        if (dueAt == null) {
            throw new IllegalArgumentException("dueAt 需要 ISO-8601 时间，例如 2026-11-11T09:00:00");
        }
        String recurrence = normalizeRecurrence(text(node, "recurrence"));

        TaskCandidate task = new TaskCandidate();
        task.setTitle(title.length() > 128 ? title.substring(0, 128) : title);
        task.setDueAt(dueAt);
        task.setRecurrence(recurrence);
        String rule = text(node, "recurrenceRule");
        if ("weekly".equals(recurrence) && !rule.isBlank()) {
            task.setRecurrenceRule(rule);
        }
        String action = text(node, "action");
        if ("mcp".equals(action)) {
            String tool = text(node, "tool");
            if (!tool.isBlank()) {
                task.setAction("mcp");
                task.setTool(tool);
                JsonNode toolArgs = node.path("toolArgs");
                if (toolArgs.isObject()) {
                    task.setToolArgs(MAPPER.convertValue(toolArgs, Map.class));
                }
            }
        } else if ("push".equals(action)) {
            task.setAction("push");
        } else {
            task.setAction("create");
        }
        plan.addTask(task);
        plan.record("create_task");
        return "已记为待确认任务：" + task.getTitle() + "（" + MINUTE.format(dueAt) + "）";
    }

    private String remember(IntentPlan plan, String args) {
        JsonNode node = read(args);
        String value = text(node, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("value 不能为空");
        }
        String key = normalizeKey(text(node, "key"));
        String memType = "dynamic".equals(text(node, "memType")) ? "dynamic" : "stable";

        MemoryCandidate memory = new MemoryCandidate();
        memory.setKey(key);
        memory.setValue(value);
        memory.setMemType(memType);
        memory.setScope("global");
        memory.setConfidence(node.hasNonNull("confidence") ? node.get("confidence").asDouble(0.9) : 0.9);
        plan.addMemory(memory);
        plan.record("remember");
        return "已记为待确认记忆：" + value;
    }

    private static JsonNode read(String args) {
        try {
            return MAPPER.readTree(args);
        } catch (Exception e) {
            throw new IllegalArgumentException("参数不是合法 JSON");
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? "" : value.asText("").trim();
    }

    static String normalizeRecurrence(String raw) {
        return switch (raw == null ? "" : raw.trim().toLowerCase()) {
            case "daily", "每天", "每日" -> "daily";
            case "weekly", "每周" -> "weekly";
            default -> "once";
        };
    }

    /** 记忆维度只剩三类：schedule 已并入任务，未知值一律按 fact 收下，不丢用户的话 */
    static String normalizeKey(String raw) {
        String key = raw == null ? "" : raw.trim().toLowerCase();
        return switch (key) {
            case "style", "relation", "fact" -> key;
            case "goal", "目标" -> "fact";
            default -> "fact";
        };
    }

    static LocalDateTime parseDue(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            return null;
        }
        String normalized = value.replace(' ', 'T');
        try {
            return LocalDateTime.parse(normalized);
        } catch (DateTimeParseException ignored) {
            // 继续尝试其它格式
        }
        try {
            return LocalDate.parse(value).atTime(9, 0);
        } catch (DateTimeParseException ignored) {
            // 交给调用方报错
        }
        return null;
    }
}
