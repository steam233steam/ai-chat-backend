package com.aichat.Service.impl;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.aichat.Config.MindButlerProperties;
import com.aichat.Dto.ConfirmResult;
import com.aichat.Dto.IntentExtractionResult;
import com.aichat.Dto.IntentOutcome;
import com.aichat.Dto.MemoryCandidate;
import com.aichat.Dto.PendingActionVO;
import com.aichat.Dto.TaskCandidate;
import com.aichat.Dto.UserDto;
import com.aichat.Exception.BusinessException;
import com.aichat.Service.IntentExtractor;
import com.aichat.Service.IntentService;
import com.aichat.Service.PendingActionService;
import com.aichat.Service.MemoryService;
import com.aichat.Service.TaskService;
import com.aichat.Service.support.IntentCandidateSanitizer;
import com.aichat.Service.support.IntentDailyLimiter;
import com.aichat.utils.IntentDetector;
import com.aichat.utils.IntentDetector.Intent;
import com.aichat.utils.IntentPayloadCodec;
import com.aichat.utils.IntentQueryReplyBuilder;
import com.aichat.utils.IntentReplyFormatter;
import com.aichat.utils.MbMarkers;
import com.aichat.utils.SensitiveContentFilter;
import com.aichat.utils.UserHolder;

/**
 * 意图服务实现：只做编排。
 *
 * 职责边界：
 * - IntentDetector：粗筛意图；
 * - IntentExtractor：一次 LLM 调用 + 超时；
 * - IntentCandidateSanitizer：候选校验/截断/敏感词；
 * - IntentPayloadCodec：pending payload 编解码；
 * - IntentReplyFormatter：确认/结果文案；
 * - PendingActionService：pending 状态机与落库事务。
 */
@Service
public class IntentServiceimpl implements IntentService {
    private static final Logger log = LoggerFactory.getLogger(IntentServiceimpl.class);

    private static final String EXTRACT_FALLBACK =
            "我没识别出要保存的记忆或任务。可以再说具体一点，比如“记住我喜欢简洁”或“明天 9 点提醒我交作业”。";
    private static final String CONFIRM_EXPIRED_FALLBACK =
            "这条确认已过期或已失效，请重新说一遍。";
    private static final String NO_PENDING_FALLBACK =
            "当前没有待确认的内容。如果要记住或提醒，请先说出具体内容。";
    private static final String UPDATE_FALLBACK =
            "P1 暂不支持通过聊天修改任务。请到任务列表手动修改，或先取消原任务再新建。";
    private static final String SENSITIVE_FALLBACK =
            "这段内容包含密码/Token/证件/银行卡等敏感信息，我不会保存。请不要在聊天中发送密码或密钥。";

    @Autowired
    private PendingActionService pendingActionService;
    @Autowired
    private IntentExtractor intentExtractor;
    @Autowired
    private IntentCandidateSanitizer candidateSanitizer;
    @Autowired
    private IntentDailyLimiter intentDailyLimiter;
    @Autowired
    private MindButlerProperties props;
    @Autowired
    private TaskService taskService;
    @Autowired
    private MemoryService memoryService;

    /** QUERY 回复里最多展示的任务/记忆条数（超出提示"列表里翻看"）。 */
    private static final int QUERY_SHOW_LIMIT = 10;

    @Override
    public IntentOutcome handle(String sessionId, String message, Long sourceMessageId) {
        // 总开关/意图开关关闭：只保留手动 CRUD，不做意图提取/确认
        if (!props.isEnabled() || !props.isIntentEnabled()) {
            return IntentOutcome.chat();
        }

        PendingActionVO pending = pendingActionService.current(sessionId);
        boolean hasPending = pending != null;
        Intent intent = IntentDetector.detect(message, hasPending);

        return switch (intent) {
            case NONE -> handleNone(sessionId, message, sourceMessageId);
            case CONFIRM -> confirm(pending);
            case REJECT -> reject(pending);
            case UPDATE -> update(pending);
            case NO_PENDING -> IntentOutcome.replyOnly(NO_PENDING_FALLBACK);
            case QUERY -> query(sessionId, message);
            case MEMORY, TASK -> extract(intent, sessionId, message, sourceMessageId);
        };
    }

    /**
     * P1.2 LLM 兜底（KouriChat 模式）：关键词粗筛 NONE 时，命中宽松触发词就过一次 LLM 提取。
     * 解决"你是猫娘"、"你的记忆怎么样"这类关键词漏过的场景。
     * LLM 提取为空 → 走正常聊天（shouldContinueChat=true），不阻断。
     */
    private IntentOutcome handleNone(String sessionId, String message, Long sourceMessageId) {
        if (props.isLlmIntentFallback() && IntentDetector.shouldTryLlmFallback(message)) {
            IntentOutcome outcome = extractLoose(sessionId, message, sourceMessageId);
            if (outcome != null) {
                return outcome;
            }
        }
        return IntentOutcome.chat();
    }

    /**
     * 宽松 LLM 提取：用 MEMORY 意图的 prompt 让 LLM 自行判断"这句有没有可提取的记忆/人设/任务"。
     * 提取到非空候选 → 走 pending 确认流程；提取为空 → 返回 null（上层走 chat）。
     */
    private IntentOutcome extractLoose(String sessionId, String message, Long sourceMessageId) {
        String userId = currentUserId();
        if (!intentDailyLimiter.tryAcquire(userId)) {
            return null; // 限额用完，走正常聊天
        }
        IntentExtractionResult extracted = intentExtractor.extract(Intent.MEMORY, message);
        if (extracted == null || extracted.isEmpty()) {
            return null; // LLM 没识别出可提取内容，走正常聊天
        }

        List<MemoryCandidate> memories = candidateSanitizer.sanitizeMemories(extracted.getMemories());
        List<TaskCandidate> tasks = candidateSanitizer.sanitizeTasks(extracted.getTasks());
        if (memories.isEmpty() && tasks.isEmpty()) {
            return null; // 净化后无有效候选，走正常聊天
        }

        String actionType = (memories.isEmpty() ? "task" : tasks.isEmpty() ? "memory" : "mixed");
        String payload = IntentPayloadCodec.buildPayload(memories, tasks, message);
        long pendingId;
        try {
            pendingId = pendingActionService.create(sessionId, actionType, payload, sourceMessageId);
        } catch (BusinessException e) {
            log.warn("LLM 兜底写 pending 失败（{}）: session={}", e.getMessage(), sessionId);
            return null;
        }

        String reply = MbMarkers.appendPending(IntentReplyFormatter.buildConfirmReply(memories, tasks), pendingId);
        log.info("intent LLM 兜底提取: session={}, pendingId={}, memories={}, tasks={}",
                sessionId, pendingId, memories.size(), tasks.size());
        return IntentOutcome.extracted(reply, pendingId);
    }

    // ---------- 查询 → 读已有数据 → 自然文案（不走 LLM，确定性回答） ----------

    /**
     * 查询意图：直接读当前用户的任务/记忆数据回答，不调 LLM。
     * - 问任务（"我有哪些任务/待办/日程"）→ 列 pending + done + 到期标记
     * - 问记忆（"你记住了我什么/你的记忆"）→ 列 active 记忆（按会话隔离取"全局+本会话"）
     * 没有数据时明确说"还没有"，不再走"没识别出"兜底。
     */
    private IntentOutcome query(String sessionId, String message) {
        if (isMemoryQuery(message)) {
            // P1.2：按会话隔离查记忆（"你的记忆"→全局+本会话），避免别的会话的人设串进来
            List<com.aichat.Dto.MemoryVO> memories = memoryService.listForInjection(
                    props.getMemoryInjectMaxStable(), props.getMemoryInjectMaxDynamic(), sessionId);
            return IntentOutcome.replyOnly(IntentQueryReplyBuilder.buildMemoryList(memories, QUERY_SHOW_LIMIT));
        }
        // 默认查任务
        List<com.aichat.Dto.TaskVO> pending = taskService.list("pending", null, QUERY_SHOW_LIMIT);
        List<com.aichat.Dto.TaskVO> done = taskService.list("done", null, QUERY_SHOW_LIMIT);
        List<com.aichat.Dto.TaskVO> due = taskService.due(QUERY_SHOW_LIMIT);
        return IntentOutcome.replyOnly(
                IntentQueryReplyBuilder.buildTaskList(pending, done, due, QUERY_SHOW_LIMIT));
    }

    /** 判断查询是问记忆还是问任务（默认任务）。P1.2：加"你的记忆"模式。 */
    private boolean isMemoryQuery(String message) {
        String lowered = message.toLowerCase(java.util.Locale.ROOT);
        return lowered.contains("记忆") || lowered.contains("记住了") || lowered.contains("记得")
                || lowered.contains("你了解") || lowered.contains("你记我");
    }

    // ---------- 提取 → 校验 → 写 pending → 确认文案 ----------

    private IntentOutcome extract(Intent intent, String sessionId, String message, Long sourceMessageId) {
        // 记忆类消息如果本身含密码/Token 等敏感信息，连提取模型都不要调用，避免把敏感内容发给第三方模型
        if (intent == Intent.MEMORY && SensitiveContentFilter.detectSensitive(message) != null) {
            return IntentOutcome.replyOnly(SENSITIVE_FALLBACK);
        }
        String userId = currentUserId();
        if (!intentDailyLimiter.tryAcquire(userId)) {
            return IntentOutcome.replyOnly("今天的意图识别次数已用完，明天再试。");
        }

        IntentExtractionResult extracted = intentExtractor.extract(intent, message);
        if (extracted == null || extracted.isEmpty()) {
            // 提取器可能按 prompt 规则主动不提取敏感信息；这时仍要给用户明确提示
            if (intent == Intent.MEMORY && SensitiveContentFilter.detectSensitive(message) != null) {
                return IntentOutcome.replyOnly(SENSITIVE_FALLBACK);
            }
            return IntentOutcome.replyOnly(EXTRACT_FALLBACK); // v3 8.4：固定兜底，不调聊天模型
        }

        List<MemoryCandidate> memories = candidateSanitizer.sanitizeMemories(extracted.getMemories());
        List<TaskCandidate> tasks = candidateSanitizer.sanitizeTasks(extracted.getTasks());
        if (memories.isEmpty() && tasks.isEmpty()) {
            if (candidateSanitizer.containsSensitiveMemory(extracted.getMemories())
                    || (intent == Intent.MEMORY && SensitiveContentFilter.detectSensitive(message) != null)) {
                return IntentOutcome.replyOnly(SENSITIVE_FALLBACK);
            }
            return IntentOutcome.replyOnly(EXTRACT_FALLBACK);
        }

        String actionType = (memories.isEmpty() ? "task" : tasks.isEmpty() ? "memory" : "mixed");
        String payload = IntentPayloadCodec.buildPayload(memories, tasks, message);
        long pendingId;
        try {
            pendingId = pendingActionService.create(sessionId, actionType, payload, sourceMessageId);
        } catch (BusinessException e) {
            // 并发 pending_slot 冲突 / payload 超限：不要让 ChatService 回退去调聊天模型
            log.warn("写 pending 失败（{}）: session={}", e.getMessage(), sessionId);
            return IntentOutcome.replyOnly("刚才的提案发生冲突，请再试一次。");
        }

        String reply = MbMarkers.appendPending(IntentReplyFormatter.buildConfirmReply(memories, tasks), pendingId);
        log.info("intent 提取: session={}, pendingId={}, memories={}, tasks={}",
                sessionId, pendingId, memories.size(), tasks.size());
        return IntentOutcome.extracted(reply, pendingId);
    }

    // ---------- 确认 / 拒绝 ----------

    private IntentOutcome confirm(PendingActionVO pending) {
        if (pending == null) {
            // 有确认词但没 pending（比如 pending 刚过期）→ 当普通聊天
            return IntentOutcome.chat();
        }
        ConfirmResult result;
        try {
            result = pendingActionService.confirm(pending.getId());
        } catch (BusinessException e) {
            // 409 已拒绝/已过期：固定说明，不调聊天模型
            log.info("confirm 失败（{}）: pendingId={}", e.getMessage(), pending.getId());
            return IntentOutcome.replyOnly(CONFIRM_EXPIRED_FALLBACK);
        }
        if (result.isAlreadyConfirmed()) {
            return IntentOutcome.confirmed(result, "刚才已经确认过了，没有重复创建。");
        }
        return IntentOutcome.confirmed(result, IntentReplyFormatter.buildConfirmResultReply(result));
    }

    /** P1 不支持改任务；如果当前还有 pending，先让它失效，避免用户之后误确认旧提案。 */
    private IntentOutcome update(PendingActionVO pending) {
        if (pending != null) {
            try {
                pendingActionService.reject(pending.getId());
            } catch (BusinessException e) {
                log.info("update 前清理 pending 失败（{}）: pendingId={}", e.getMessage(), pending.getId());
            }
        }
        return IntentOutcome.replyOnly(UPDATE_FALLBACK);
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

    private String currentUserId() {
        UserDto user = UserHolder.getUser();
        if (user == null || user.getId() == null) {
            throw new BusinessException(401, "请先登录");
        }
        return user.getId();
    }
}
