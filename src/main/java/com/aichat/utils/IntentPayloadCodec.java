package com.aichat.utils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aichat.Dto.MemoryCandidate;
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
