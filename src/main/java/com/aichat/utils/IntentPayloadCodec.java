package com.aichat.utils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aichat.Dto.MemoryCandidate;
import com.aichat.Dto.MemoryChange;
import com.aichat.Dto.TaskCandidate;
import com.aichat.Exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * pending payload 的唯一编解码入口。
 *
 * 之前 IntentService 写 JSON、PendingActionService 又各自解析 JSON，格式细节散落两处；
 * 现在统一到 codec，payload 结构变化只需要改一个文件。
 */
public final class IntentPayloadCodec {
    private static final Logger log = LoggerFactory.getLogger(IntentPayloadCodec.class);
    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    private IntentPayloadCodec() {
    }

    public static String buildPayload(List<MemoryCandidate> memories, List<TaskCandidate> tasks,
            String rawInput) {
        try {
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("memories", memories);
            root.put("tasks", tasks);
            root.put("rawInput", rawInput);
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            throw new BusinessException("生成待确认内容失败，请重新说一遍");
        }
    }

    /** 记忆变更提案（删除/修改）的 payload：与新建记忆/任务分开，确认时按 actionType 分派。 */
    public static String buildChangePayload(List<MemoryChange> changes, String preview, String rawInput) {
        try {
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("changes", changes);
            root.put("preview", preview);
            root.put("rawInput", rawInput);
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            throw new BusinessException("生成待确认内容失败，请重新说一遍");
        }
    }

    /** 读记忆变更提案；结构不对返回空列表，交给上层按"没找到要改的记忆"处理。 */
    public static List<MemoryChange> parseChanges(String payload) {
        return parseCandidates(payload, "changes", MemoryChange.class);
    }

    /** 顶层字符串字段（例如变更提案里的 preview）。 */
    public static String parsePreview(String payload) {
        if (payload == null || payload.isBlank()) {
            return "";
        }
        try {
            JsonNode node = MAPPER.readTree(payload).get("preview");
            return node == null || node.isNull() ? "" : node.asText("");
        } catch (Exception e) {
            log.warn("解析 pending preview 失败: {}", e.getMessage());
            return "";
        }
    }
    /** 从 payload JSON 的指定数组字段解析候选列表（解析失败返回空，不抛）。 */
    public static <T> List<T> parseCandidates(String payload, String field, Class<T> type) {
        List<T> list = new ArrayList<>();
        if (payload == null || payload.isBlank()) {
            return list;
        }
        try {
            JsonNode root = MAPPER.readTree(payload);
            JsonNode arr = root.get(field);
            if (arr == null || !arr.isArray()) {
                return list;
            }
            for (JsonNode node : arr) {
                list.add(MAPPER.convertValue(node, type));
            }
        } catch (Exception e) {
            log.warn("解析 pending payload 失败，按空处理: {}", e.getMessage());
        }
        return list;
    }

    /**
     * 把「只含要改字段」的补丁合进草案的第一个任务。
     * <p>补丁里为 null 的字段表示「不改」，保持原值；返回新的 payload JSON。
     */
    public static String patchTaskPayload(String payload, TaskCandidate patch) {
        if (patch == null) {
            throw new BusinessException("没有要修改的内容");
        }
        try {
            com.fasterxml.jackson.databind.node.ObjectNode root =
                    (com.fasterxml.jackson.databind.node.ObjectNode) MAPPER.readTree(payload);
            JsonNode tasks = root.path("tasks");
            if (!tasks.isArray() || tasks.isEmpty()) {
                throw new BusinessException("当前草案里没有可修改的任务");
            }
            com.fasterxml.jackson.databind.node.ObjectNode task =
                    (com.fasterxml.jackson.databind.node.ObjectNode) tasks.get(0);
            if (patch.getTitle() != null) {
                task.put("title", patch.getTitle());
            }
            if (patch.getDueAt() != null) {
                task.put("dueAt", patch.getDueAt().toString());
            }
            if (patch.getRecurrence() != null) {
                task.put("recurrence", patch.getRecurrence());
            }
            if (patch.getRecurrenceRule() != null) {
                task.put("recurrenceRule", patch.getRecurrenceRule());
            }
            if (patch.getTool() != null) {
                task.put("tool", patch.getTool());
                task.put("action", "mcp");
            }
            if (patch.getToolArgs() != null) {
                task.set("toolArgs", MAPPER.valueToTree(patch.getToolArgs()));
            }
            if (patch.getAction() != null && !"mcp".equals(task.path("action").asText())) {
                task.put("action", patch.getAction());
            }
            return MAPPER.writeValueAsString(root);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("修改草案失败，请重新说一遍");
        }
    }

    /** 对比两份任务草案，产出人话差异（无差异返回空串）。 */
    public static String describeTaskChanges(String oldPayload, String newPayload) {
        try {
            com.fasterxml.jackson.databind.node.ObjectNode before =
                    (com.fasterxml.jackson.databind.node.ObjectNode) MAPPER.readTree(oldPayload);
            com.fasterxml.jackson.databind.node.ObjectNode after =
                    (com.fasterxml.jackson.databind.node.ObjectNode) MAPPER.readTree(newPayload);
            JsonNode b = before.path("tasks").path(0);
            JsonNode a = after.path("tasks").path(0);
            List<String> lines = new ArrayList<>();
            diffLine(lines, "标题", b.path("title").asText(""), a.path("title").asText(""));
            diffLine(lines, "时间", humanTime(b.path("dueAt").asText("")), humanTime(a.path("dueAt").asText("")));
            diffLine(lines, "重复", b.path("recurrence").asText(""), a.path("recurrence").asText(""));
            diffLine(lines, "动作", b.path("action").asText(""), a.path("action").asText(""));
            diffLine(lines, "工具", b.path("tool").asText(""), a.path("tool").asText(""));
            return String.join("\n", lines);
        } catch (Exception e) {
            log.warn("对比草案差异失败: {}", e.getMessage());
            return "";
        }
    }

    /** ISO 时间转人话：2026-11-15T08:30:00 → 2026-11-15 08:30 */
    private static String humanTime(String iso) {
        if (iso == null || iso.isBlank()) {
            return "";
        }
        String text = iso.replace('T', ' ');
        return text.length() > 16 ? text.substring(0, 16) : text;
    }

    private static void diffLine(List<String> lines, String label, String before, String after) {
        if (before.equals(after)) {
            return;
        }
        lines.add("· " + label + "：" + (before.isBlank() ? "（空）" : before)
                + " → " + (after.isBlank() ? "（空）" : after));
    }
}
