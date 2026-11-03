package com.aichat.Service.impl;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.aichat.Config.MindButlerProperties;
import com.aichat.Dto.ConfirmResult;
import com.aichat.Dto.MemoryCandidate;
import com.aichat.Dto.PendingActionVO;
import com.aichat.Dto.TaskCandidate;
import com.aichat.Exception.BusinessException;
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

    @Autowired
    private PendingActionService pendingActionService;
    @Autowired
    private IntentExtractor intentExtractor;
    @Autowired
    private MindButlerProperties props;

    @Override
    public IntentOutcome handle(String sessionId, String message, Long sourceMessageId) {
        // 总开关关闭：只保留手动 CRUD，不做意图提取/确认
        if (!props.isEnabled()) {
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
        Parsed parsed = intentExtractor.extract(intent, message);
        if (parsed == null || parsed.isEmpty()) {
            return IntentOutcome.chat(); // 没识别出候选，回退正常聊天（不 500）
        }

        // 校验：过滤敏感内容 + 非法 memKey + 非 create 任务
        List<MemoryCandidate> memories = sanitizeMemories(parsed.memories);
        List<TaskCandidate> tasks = sanitizeTasks(parsed.tasks);
        if (memories.isEmpty() && tasks.isEmpty()) {
            return IntentOutcome.chat(); // 全部被过滤掉，回退正常聊天
        }

        // 写 pending（旧 pending 自动 expired；action_type 按实际内容分 memory/task/mixed）
        String actionType = (memories.isEmpty() ? "task" : tasks.isEmpty() ? "memory" : "mixed");
        String payload = buildPayload(memories, tasks, message);
        long pendingId = pendingActionService.create(sessionId, actionType, payload, sourceMessageId);

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
            // 409 已拒绝/已过期：给一句说明，不当聊天
            log.info("confirm 失败（{}）: pendingId={}", e.getMessage(), pending.getId());
            return IntentOutcome.chat();
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
            return IntentOutcome.chat();
        }
        return IntentOutcome.rejected("好的，已取消这条待确认。");
    }

    // ---------- 候选净化 ----------

    private List<MemoryCandidate> sanitizeMemories(List<MemoryCandidate> in) {
        List<MemoryCandidate> out = new java.util.ArrayList<>();
        if (in == null) {
            return out;
        }
        for (MemoryCandidate c : in) {
            if (c == null || !MemoryKey.isValid(c.getKey())) {
                continue; // 非法/缺 key 的候选丢弃
            }
            String sensitive = SensitiveContentFilter.detectSensitive(c.getValue());
            if (sensitive != null) {
                log.warn("跳过含{}的记忆候选: key={}", sensitive, c.getKey());
                continue;
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
        for (TaskCandidate c : in) {
            if (c == null || c.getTitle() == null || c.getTitle().isBlank()) {
                continue;
            }
            // P1 只允许 create（update/delete 留 P1.5）
            if (c.getAction() != null && !"create".equals(c.getAction())) {
                continue;
            }
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
            parts.add("记忆「" + m.getKey() + "=" + m.getValue() + "」");
        }
        for (TaskCandidate t : tasks) {
            parts.add("任务「" + t.getTitle() + "」");
        }
        sb.append(String.join("、", parts)).append("。确认吗？（回复 对/确认 生效，取消则不保存）");
        return sb.toString();
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
                parts.add("记忆 " + m.get("id"));
            }
        }
        if (tasks != null) {
            for (java.util.Map<String, Object> t : tasks) {
                parts.add("任务 " + t.get("id"));
            }
        }
        sb.append(String.join("、", parts)).append("。");
        return sb.toString();
    }
}
