package com.aichat.Dto;

import java.util.List;

/**
 * 意图提取结果：LLM/解析器和 Service 之间的边界对象。
 *
 * 放在 Dto 而不是 IntentJsonParser 的嵌套类：
 * - IntentExtractor 接口不再依赖 utils.IntentJsonParser；
 * - 解析器只负责“JSON -> DTO”，Service 只消费 DTO；
 * - 列表不可变，避免调用方把它当可变缓存改掉。
 */
public final class IntentExtractionResult {

    private final List<MemoryCandidate> memories;
    private final List<TaskCandidate> tasks;

    public IntentExtractionResult(List<MemoryCandidate> memories, List<TaskCandidate> tasks) {
        this.memories = memories == null ? List.of() : List.copyOf(memories);
        this.tasks = tasks == null ? List.of() : List.copyOf(tasks);
    }

    public List<MemoryCandidate> getMemories() {
        return memories;
    }

    public List<TaskCandidate> getTasks() {
        return tasks;
    }

    public boolean isEmpty() {
        return memories.isEmpty() && tasks.isEmpty();
    }
}
