package com.aichat.Service.impl;

import java.util.ArrayList;
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
import com.aichat.Dto.MemoryChange;
import com.aichat.Dto.MemoryVO;
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
import com.aichat.Service.support.MemoryChangeClarifyStore;
import com.aichat.utils.IntentDetector;
import com.aichat.utils.IntentDetector.Intent;
import com.aichat.utils.IntentPayloadCodec;
import com.aichat.utils.IntentQueryReplyBuilder;
import com.aichat.utils.IntentReplyFormatter;
import com.aichat.utils.MbMarkers;
import com.aichat.utils.MemoryChangeParser;
import com.aichat.utils.SensitiveContentFilter;
import com.aichat.utils.TaskCapabilityGuard;
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
public class IntentServiceImpl implements IntentService {
    private static final Logger log = LoggerFactory.getLogger(IntentServiceImpl.class);

    private static final String EXTRACT_FALLBACK =
            "我没识别出要保存的记忆或任务。可以再说具体一点，比如“记住我喜欢简洁”或“明天 9 点提醒我交作业”。";
    private static final String CONFIRM_EXPIRED_FALLBACK =
            "这条确认已过期或已失效，请重新说一遍。";
    private static final String NO_PENDING_FALLBACK =
            "当前没有待确认的内容。如果要记住或提醒，请先说出具体内容。";
    private static final String UPDATE_FALLBACK =
            "聊天里目前可以改或删记忆，比如“把目标改成明年考公”“把作息的记忆删掉”。"
                    + "任务的时间、重复规则要改，请到任务列表操作。";
    private static final String TASK_CLARIFY_FALLBACK =
            "我没识别出具体任务。你想让我提醒你什么？大概几点？";
    private static final String SENSITIVE_FALLBACK =
            "这段内容包含密码/Token/证件/银行卡等敏感信息，我不会保存。请不要在聊天中发送密码或密钥。";

    private final PendingActionService pendingActionService;
    private final IntentExtractor intentExtractor;
    private final IntentCandidateSanitizer candidateSanitizer;
    private final IntentDailyLimiter intentDailyLimiter;
    private final MindButlerProperties props;
    private final TaskService taskService;
    private final MemoryService memoryService;
    private final MemoryChangeClarifyStore clarifyStore;

    public IntentServiceImpl(PendingActionService pendingActionService, IntentExtractor intentExtractor, IntentCandidateSanitizer candidateSanitizer, IntentDailyLimiter intentDailyLimiter, MindButlerProperties props, TaskService taskService, MemoryService memoryService, MemoryChangeClarifyStore clarifyStore) {
        this.pendingActionService = pendingActionService;
        this.intentExtractor = intentExtractor;
        this.candidateSanitizer = candidateSanitizer;
        this.intentDailyLimiter = intentDailyLimiter;
        this.props = props;
        this.taskService = taskService;
        this.memoryService = memoryService;
        this.clarifyStore = clarifyStore;
    }

    /** 记忆变更时扫描的候选条数上限；只读生效记忆，100 是 mapper 的硬上限。 */
    private static final int MEMORY_CHANGE_SCAN_LIMIT = 50;

    /** QUERY 回复里最多展示的任务/记忆条数（超出提示"列表里翻看"）。 */
    private static final int QUERY_SHOW_LIMIT = 10;

    @Override
    public IntentOutcome handle(String sessionId, String message, Long sourceMessageId) {
        // 总开关/意图开关关闭：只保留手动 CRUD，不做意图提取/确认
        if (!props.isEnabled() || !props.isIntentEnabled()) {
            return IntentOutcome.chat();
        }

        // 上一轮在追问「改/删哪一条」：先看这句是不是在回答那个问题，
        // 否则「考公的那个」这种没有触发词的回答会被判成闲聊
        IntentOutcome clarifyOutcome = tryResolveClarify(sessionId, message, sourceMessageId);
        if (clarifyOutcome != null) {
            return clarifyOutcome;
        }

        PendingActionVO pending = pendingActionService.current(sessionId);
        boolean hasPending = pending != null;
        Intent intent = IntentDetector.detect(message, hasPending);

        return switch (intent) {
            case NONE -> handleNone(sessionId, message, sourceMessageId);
            case CONFIRM -> confirm(pending);
            case REJECT -> reject(pending);
            case UPDATE -> update(pending, sessionId, message, sourceMessageId);
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
     * 宽松 LLM 提取：让 LLM 自由判断"这句有没有可提取的记忆/人设/任务"，不预设侧重。
     * 提取到非空候选 → 走 pending 确认流程；提取为空 → 返回 null（上层走 chat）。
     */
    private IntentOutcome extractLoose(String sessionId, String message, Long sourceMessageId) {
        // 先过能力边界：QQ/微信/短信等当前没有通道的请求，不要交给提取模型生成假任务。
        String capabilityReply = TaskCapabilityGuard.unsupportedReason(message);
        if (capabilityReply != null) {
            return IntentOutcome.replyOnly(capabilityReply);
        }
        // 宽松触发词包含“我的/我是”等高频词，必须先做敏感信息闸门，
        // 否则“我的密码是 xxx”会被直接发给第三方提取模型。
        if (SensitiveContentFilter.detectSensitiveData(message) != null) {
            return IntentOutcome.replyOnly(SENSITIVE_FALLBACK);
        }
        String userId = currentUserId();
        if (!intentDailyLimiter.tryAcquire(userId)) {
            return null; // 限额用完，走正常聊天
        }
        // P1.4：不再固定传 MEMORY 侧重；让 LLM 自行判断"这句是记忆、人设、任务还是混合"
        IntentExtractionResult extracted = intentExtractor.extract(null, message);
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
            // 不要静默回退聊天：否则模型可能顺着上下文编造“已记住/已安排”。
            return IntentOutcome.replyOnly("刚才的提案发生冲突，请再试一次。");
        }

        // 弹窗自己展示详情和确认按钮，聊天流里不再插入“已识别到 N 条”的额外 AI 气泡。
        String reply = MbMarkers.pendingOnly(pendingId);
        log.info("intent LLM 兜底提取: session={}, pendingId={}, memories={}, tasks={}",
                sessionId, pendingId, memories.size(), tasks.size());
        return IntentOutcome.extracted(reply, pendingId);
    }

    // 查询 → 读已有数据 → 自然文案（不走 LLM，确定性回答）

    /**
     * 查询意图：直接读当前用户的任务/记忆数据回答，不调 LLM。
     * - 问任务（"我有哪些任务/待办/日程"）→ 列 pending + done + 到期标记
     * - 问记忆（"你记住了我什么/你的记忆"）→ 列 active 记忆（按会话隔离取"全局+本会话"）
     * 没有数据时明确说"还没有"，不再走"没识别出"兜底。
     */
    private IntentOutcome query(String sessionId, String message) {
        if (isMemoryQuery(message)) {
            // 查询是“用户查看自己的数据”，不是 prompt 注入：不能用注入配额（默认 5+5）截断，
            // 否则用户有 20 条记忆时只会被告知 10 条。list() 会返回全局+本会话的 active 记忆。
            List<com.aichat.Dto.MemoryVO> memories = memoryService.list(100, sessionId);
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

    // 提取 → 校验 → 写 pending → 确认文案

    private IntentOutcome extract(Intent intent, String sessionId, String message, Long sourceMessageId) {
        if (intent == Intent.TASK) {
            // P1 没有外部投递通道：明确拒绝 QQ/微信/短信等请求，并给出“改成应用内任务”的替代说法。
            String capabilityReply = TaskCapabilityGuard.unsupportedReason(message);
            if (capabilityReply != null) {
                return IntentOutcome.replyOnly(capabilityReply);
            }
        }
        // 记忆/任务类消息如果本身含密码/Token 等敏感信息，连提取模型都不要调用，避免把敏感内容发给第三方模型
        if ((intent == Intent.MEMORY || intent == Intent.TASK)
                && SensitiveContentFilter.detectSensitive(message) != null) {
            return IntentOutcome.replyOnly(SENSITIVE_FALLBACK);
        }
        String userId = currentUserId();
        if (!intentDailyLimiter.tryAcquire(userId)) {
            return IntentOutcome.replyOnly("今天的意图识别次数已用完，明天再试。");
        }

        IntentExtractionResult extracted = intentExtractor.extract(intent, message);
        if (extracted == null || extracted.isEmpty()) {
            // 提取器可能按 prompt 规则主动不提取敏感信息；这时仍要给用户明确提示
            if ((intent == Intent.MEMORY || intent == Intent.TASK)
                    && SensitiveContentFilter.detectSensitive(message) != null) {
                return IntentOutcome.replyOnly(SENSITIVE_FALLBACK);
            }
            return IntentOutcome.replyOnly(
                    intent == Intent.TASK ? TASK_CLARIFY_FALLBACK : EXTRACT_FALLBACK);
        }

        List<MemoryCandidate> memories = candidateSanitizer.sanitizeMemories(extracted.getMemories());
        List<TaskCandidate> tasks = candidateSanitizer.sanitizeTasks(extracted.getTasks());
        if (memories.isEmpty() && tasks.isEmpty()) {
            if (candidateSanitizer.containsSensitiveMemory(extracted.getMemories())
                    || candidateSanitizer.containsSensitiveTask(extracted.getTasks())
                    || ((intent == Intent.MEMORY || intent == Intent.TASK)
                        && SensitiveContentFilter.detectSensitive(message) != null)) {
                return IntentOutcome.replyOnly(SENSITIVE_FALLBACK);
            }
            return IntentOutcome.replyOnly(
                    intent == Intent.TASK ? TASK_CLARIFY_FALLBACK : EXTRACT_FALLBACK);
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

        // 弹窗自己展示详情和确认按钮，聊天流里不再插入“已识别到 N 条”的额外 AI 气泡。
        String reply = MbMarkers.pendingOnly(pendingId);
        log.info("intent 提取: session={}, pendingId={}, memories={}, tasks={}",
                sessionId, pendingId, memories.size(), tasks.size());
        return IntentOutcome.extracted(reply, pendingId);
    }

    // 确认 / 拒绝

    private IntentOutcome confirm(PendingActionVO pending) {
        if (pending == null) {
            // 有确认词但没 pending（比如 pending 刚过期）→ 当普通聊天
            return IntentOutcome.chat();
        }
        ConfirmResult result;
        try {
            result = pendingActionService.confirm(pending.getId());
        } catch (BusinessException e) {
            // 不能把所有失败都伪装成“过期”：记忆上限、任务校验失败等也要把真实原因告诉用户。
            log.info("confirm 失败（{}）: pendingId={}", e.getMessage(), pending.getId());
            String message = e.getMessage();
            return IntentOutcome.replyOnly(
                    message == null || message.isBlank() ? CONFIRM_EXPIRED_FALLBACK : message);
        }
        if (result.isAlreadyConfirmed()) {
            return IntentOutcome.confirmed(result, "刚才已经确认过了，没有重复创建。");
        }
        return IntentOutcome.confirmed(result, IntentReplyFormatter.buildConfirmResultReply(result));
    }

    /**
     * 用户说“把 X 改成 Y”“把 X 删掉”。
     *
     * 先用规则在已有记忆里定位目标（MemoryChangeParser），定位到就发一条 memory_change 提案，
     * 走和新建相同的确认弹窗；定位不到或有多条候选就追问，不猜、不落库。
     *
     * 任务改造/删除不在这一步：任务还牵涉改时间、重复规则，留到 P1.5 余下部分。
     * 如果有未确认的旧提案，先作废，避免用户之后误确认。
     */
    private IntentOutcome update(PendingActionVO pending, String sessionId, String message,
            Long sourceMessageId) {
        if (pending != null) {
            try {
                pendingActionService.reject(pending.getId());
            } catch (BusinessException e) {
                log.info("update 前清理 pending 失败（{}）: pendingId={}", e.getMessage(), pending.getId());
            }
        }
        // 改后的内容也可能含密码/证件号，先过敏感信息闸门，别把它写进 pending payload
        if (SensitiveContentFilter.detectSensitiveData(message) != null) {
            return IntentOutcome.replyOnly(SENSITIVE_FALLBACK);
        }
        // 说的是任务（改时间/删任务）：这轮还没做，明确引导走任务列表，别回“没找到记忆”
        if (MemoryChangeParser.looksLikeTaskChange(message)) {
            return IntentOutcome.replyOnly(UPDATE_FALLBACK);
        }
        MemoryChangeParser.Resolution resolution =
                MemoryChangeParser.parse(message, memoryService.list(MEMORY_CHANGE_SCAN_LIMIT));
        if (!resolution.resolved()) {
            // 候选不止一条：把候选存起来，用户下一句「考公的那个」才接得上
            if (resolution.candidates().size() > 1) {
                MemoryChangeParser.Action action = MemoryChangeParser.actionOf(message);
                clarifyStore.savePick(currentUserId(), sessionId,
                        action == null ? MemoryChangeClarifyStore.ACTION_UPDATE
                                : (action == MemoryChangeParser.Action.DELETE
                                        ? MemoryChangeClarifyStore.ACTION_DELETE
                                        : MemoryChangeClarifyStore.ACTION_UPDATE),
                        resolution.candidates());
            }
            return IntentOutcome.replyOnly(resolution.clarify());
        }

        MemoryChangeParser.Plan plan = resolution.plan();
        List<MemoryChange> changes = new ArrayList<>();
        for (MemoryVO target : plan.targets()) {
            changes.add(new MemoryChange(target.getId(),
                    plan.isDelete() ? "DELETE" : "UPDATE", target.getContent(), plan.after()));
        }
        long pendingId;
        try {
            pendingId = pendingActionService.create(sessionId, "memory_change",
                    IntentPayloadCodec.buildChangePayload(changes, plan.preview(), message),
                    sourceMessageId);
        } catch (BusinessException e) {
            log.warn("记忆变更写 pending 失败（{}）: session={}", e.getMessage(), sessionId);
            return IntentOutcome.replyOnly("刚才的提案发生冲突，请再试一次。");
        }
        log.info("记忆变更提案: session={}, pendingId={}, action={}, targets={}", sessionId, pendingId,
                plan.action(), plan.targets().size());
        return IntentOutcome.extracted(MbMarkers.pendingOnly(pendingId), pendingId);
    }

    /**
     * 承接上一轮的追问（「我找到 2 条…你想改哪一条？」）。
     *
     * 返回 null 表示这句不是在回答追问，交回正常流程处理。
     */
    private IntentOutcome tryResolveClarify(String sessionId, String message, Long sourceMessageId) {
        String userId = currentUserId();
        MemoryChangeClarifyStore.State state = clarifyStore.load(userId, sessionId);
        if (state == null) {
            return null;
        }
        String text = message == null ? "" : message.trim();
        if (text.isEmpty()) {
            clarifyStore.clear(userId, sessionId);
            return null;
        }
        boolean changeRequest = MemoryChangeParser.looksLikeChangeRequest(text);

        // 已选好目标、只差新值：整句当新内容；带了"改成"就按触发词切
        if (MemoryChangeClarifyStore.STAGE_VALUE.equals(MemoryChangeClarifyStore.stageOf(state))) {
            clarifyStore.clear(userId, sessionId);
            String after = MemoryChangeParser.extractAfterByTrigger(text);
            if (after.isBlank()) {
                after = MemoryChangeParser.extractAfterLoose(text);
            }
            return createChangeOutcome(sessionId, MemoryChangeClarifyStore.ACTION_UPDATE, state.targetId(),
                    state.targetContent(), after, sourceMessageId);
        }

        List<String> contents = new ArrayList<>();
        for (MemoryChangeClarifyStore.Candidate c : state.candidates()) {
            contents.add(c.content());
        }
        List<Integer> hits = MemoryChangeParser.pickCandidateIndexes(text, contents);
        MemoryChangeClarifyStore.Candidate chosen = hits.size() == 1 ? state.candidates().get(hits.get(0)) : null;
        if (chosen == null && hits.isEmpty() && state.candidates().size() == 1) {
            chosen = state.candidates().get(0);
        }

        if (chosen == null) {
            if (changeRequest) {
                // 用户换了个新诉求（「那我删掉风格那条」）：清掉旧追问，交回正常解析
                clarifyStore.clear(userId, sessionId);
                return null;
            }
            // 还是没听懂：再问一次，TTL 顺延
            clarifyStore.savePick(userId, sessionId, state.action(), toMemoryVOs(state.candidates()));
            return IntentOutcome.replyOnly(stillAmbiguous(state.candidates()));
        }

        clarifyStore.clear(userId, sessionId);
        if (MemoryChangeClarifyStore.ACTION_DELETE.equals(state.action())) {
            return createChangeOutcome(sessionId, MemoryChangeClarifyStore.ACTION_DELETE, chosen.id(),
                    chosen.content(), null, sourceMessageId);
        }
        String after = MemoryChangeParser.extractAfterByTrigger(text);
        if (after.isBlank() || after.equals(chosen.content())) {
            // 只说了"哪一条"、没给新内容：存目标，下一句整句当新值
            clarifyStore.saveValue(userId, sessionId, chosen.id(), chosen.content());
            return IntentOutcome.replyOnly("你想把「" + chosen.content() + "」改成什么？直接说新的内容就行。");
        }
        return createChangeOutcome(sessionId, MemoryChangeClarifyStore.ACTION_UPDATE, chosen.id(),
                chosen.content(), after, sourceMessageId);
    }

    /** 澄清后命中目标：建 memory_change 提案（和 update() 走同一套 payload）。 */
    private IntentOutcome createChangeOutcome(String sessionId, String action, Long targetId,
            String targetContent, String after, Long sourceMessageId) {
        boolean delete = MemoryChangeClarifyStore.ACTION_DELETE.equals(action);
        if (!delete) {
            if (after == null || after.isBlank()) {
                return IntentOutcome.replyOnly("改成什么？直接说新的内容就行。");
            }
            if (SensitiveContentFilter.detectSensitiveData(after) != null) {
                return IntentOutcome.replyOnly(SENSITIVE_FALLBACK);
            }
        }
        PendingActionVO pending = pendingActionService.current(sessionId);
        if (pending != null) {
            try {
                pendingActionService.reject(pending.getId());
            } catch (BusinessException e) {
                log.info("澄清后清理 pending 失败（{}）: pendingId={}", e.getMessage(), pending.getId());
            }
        }
        List<MemoryChange> changes = new ArrayList<>();
        changes.add(new MemoryChange(targetId, delete ? "DELETE" : "UPDATE", targetContent,
                delete ? null : after));
        String preview = delete
                ? "要删掉这条记忆：" + targetContent + "。删掉后我不会再拿它回答你。"
                : "要把这条记忆改成：「" + after + "」。原来是：" + targetContent + "。";
        try {
            long pendingId = pendingActionService.create(sessionId, "memory_change",
                    IntentPayloadCodec.buildChangePayload(changes, preview, delete ? targetContent : after),
                    sourceMessageId);
            log.info("澄清后记忆变更提案: session={}, pendingId={}, action={}", sessionId, pendingId, action);
            return IntentOutcome.extracted(MbMarkers.pendingOnly(pendingId), pendingId);
        } catch (BusinessException e) {
            log.warn("澄清后写 pending 失败（{}）: session={}", e.getMessage(), sessionId);
            return IntentOutcome.replyOnly("刚才的提案发生冲突，请再试一次。");
        }
    }

    private String stillAmbiguous(List<MemoryChangeClarifyStore.Candidate> candidates) {
        StringBuilder sb = new StringBuilder("还是没确定是哪一条：");
        int limit = Math.min(candidates.size(), 4);
        for (int i = 0; i < limit; i++) {
            sb.append("第").append(i + 1).append("条「").append(candidates.get(i).content()).append("」");
            if (i < limit - 1) {
                sb.append("、");
            }
        }
        sb.append("。可以说「第 1 条」，或者带上关键词，比如「考公的那个」。");
        return sb.toString();
    }

    private static List<MemoryVO> toMemoryVOs(List<MemoryChangeClarifyStore.Candidate> candidates) {
        List<MemoryVO> list = new ArrayList<>();
        for (MemoryChangeClarifyStore.Candidate c : candidates) {
            MemoryVO m = new MemoryVO();
            m.setId(c.id());
            m.setMemKey(c.memKey());
            m.setContent(c.content());
            list.add(m);
        }
        return list;
    }

    private IntentOutcome reject(PendingActionVO pending) {        if (pending == null) {
            return IntentOutcome.chat();
        }
        try {
            boolean changed = pendingActionService.reject(pending.getId());
            log.info("文本取消 pending: pendingId={}, changed={}", pending.getId(), changed);
        } catch (BusinessException e) {
            log.info("reject 失败（{}）: pendingId={}", e.getMessage(), pending.getId());
            String message = e.getMessage();
            return IntentOutcome.replyOnly(
                    message == null || message.isBlank() ? CONFIRM_EXPIRED_FALLBACK : message);
        }
        return IntentOutcome.rejected("计划取消");
    }

    private String currentUserId() {
        UserDto user = UserHolder.getUser();
        if (user == null || user.getId() == null) {
            throw new BusinessException(401, "请先登录");
        }
        return user.getId();
    }
}
