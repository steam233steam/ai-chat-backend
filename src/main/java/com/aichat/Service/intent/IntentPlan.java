package com.aichat.Service.intent;

import java.util.ArrayList;
import java.util.List;

import com.aichat.Dto.MemoryCandidate;
import com.aichat.Dto.TaskCandidate;

/**
 * 一次意图抽取过程中，模型通过工具调用产出的「计划」。
 *
 * <p>工具回调本身不改库、不建 pending，只把结构化参数放进这里；
 * 抽取结束后由 {@link IntentToolEngine} 统一转成 {@code IntentExtractionResult}，
 * 下游仍走原来的「候选 → 校验 → 待确认 → 落库」链路。
 *
 * <p>这样模型能一次说清多件事（一句话里既有记忆又有任务，甚至将来的动作链），
 * 而"要不要真的执行"这个决定仍然在用户手里。
 */
public class IntentPlan {

    private final List<MemoryCandidate> memories = new ArrayList<>();
    private final List<TaskCandidate> tasks = new ArrayList<>();
    private final List<String> calls = new ArrayList<>();

    public void addMemory(MemoryCandidate memory) {
        memories.add(memory);
    }

    public void addTask(TaskCandidate task) {
        tasks.add(task);
    }

    public void record(String toolName) {
        calls.add(toolName);
    }

    /**
     * 修改草案时模型给出的「补丁」：只有用户这次提到的字段有值，其余保持 null 表示不改。
     * 与 {@link #tasks} 分开存，避免和「新建任务」混在一起被当成新任务落库。
     */
    private TaskCandidate patch;

    public void setPatch(TaskCandidate patch) {
        this.patch = patch;
    }

    public TaskCandidate getPatch() {
        return patch;
    }

    public boolean hasPatch() {
        return patch != null;
    }

    public List<MemoryCandidate> getMemories() {
        return memories;
    }

    public List<TaskCandidate> getTasks() {
        return tasks;
    }

    /** 模型依次调了哪些工具（排查"到底有没有调工具"用） */
    public List<String> getCalls() {
        return calls;
    }

    public boolean isEmpty() {
        return memories.isEmpty() && tasks.isEmpty();
    }
}
