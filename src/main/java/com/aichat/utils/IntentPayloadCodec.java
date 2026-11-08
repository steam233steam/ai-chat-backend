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
}
