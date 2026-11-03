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
import com.aichat.Service.support.IntentCandidateSanitizer;
import com.aichat.Service.support.IntentDailyLimiter;
import com.aichat.utils.IntentDetector;
import com.aichat.utils.IntentDetector.Intent;
import com.aichat.utils.IntentPayloadCodec;
import com.aichat.utils.IntentReplyFormatter;
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
            case NONE -> IntentOutcome.chat();
            case CONFIRM -> confirm(pending);
            case REJECT -> reject(pending);
            case MEMORY, TASK -> extract(intent, sessionId, message, sourceMessageId);
        };
    }

    // ---------- 提取 → 校验 → 写 pending → 确认文案 ----------

    private IntentOutcome extract(Intent intent, String sessionId, String message, Long sourceMessageId) {
        String userId = currentUserId();
        if (!intentDailyLimiter.tryAcquire(userId)) {
            return IntentOutcome.replyOnly("今天的意图识别次数已用完，明天再试。");
        }

        IntentExtractionResult extracted = intentExtractor.extract(intent, message);
        if (extracted == null || extracted.isEmpty()) {
            return IntentOutcome.replyOnly(EXTRACT_FALLBACK); // v3 8.4：固定兜底，不调聊天模型
        }

        List<MemoryCandidate> memories = candidateSanitizer.sanitizeMemories(extracted.getMemories());
        List<TaskCandidate> tasks = candidateSanitizer.sanitizeTasks(extracted.getTasks());
        if (memories.isEmpty() && tasks.isEmpty()) {
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

        String reply = IntentReplyFormatter.buildConfirmReply(memories, tasks);
        log.info("intent 提取: session={}, pendingId={}, memories={}, tasks={}",
                sessionId, pendingId, memories.size(), tasks.size());
        return IntentOutcome.extracted(reply);
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
