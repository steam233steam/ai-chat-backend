package com.aichat.Service.impl;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aichat.Config.MindButlerProperties;
import com.aichat.Dto.ConfirmResult;
import com.aichat.Dto.CreateTaskRequest;
import com.aichat.Dto.MemoryCandidate;
import com.aichat.Dto.MemoryVO;
import com.aichat.Dto.PendingActionVO;
import com.aichat.Dto.TaskCandidate;
import com.aichat.Dto.TaskVO;
import com.aichat.Dto.UserDto;
import com.aichat.Entity.PendingAction;
import com.aichat.Exception.BusinessException;
import com.aichat.Mapper.PendingActionMapper;
import com.aichat.Service.MemoryService;
import com.aichat.Service.PendingActionService;
import com.aichat.Service.TaskService;
import com.aichat.utils.IntentPayloadCodec;
import com.aichat.utils.UserHolder;

@Service
public class PendingActionServiceImpl implements PendingActionService {
    private static final Logger log = LoggerFactory.getLogger(PendingActionServiceImpl.class);

    private final PendingActionMapper pendingActionMapper;
    private final MemoryService memoryService;
    private final TaskService taskService;
    private final MindButlerProperties props;
    private final Clock clock;

    public PendingActionServiceImpl(PendingActionMapper pendingActionMapper, MemoryService memoryService, TaskService taskService, MindButlerProperties props, Clock clock) {
        this.pendingActionMapper = pendingActionMapper;
        this.memoryService = memoryService;
        this.taskService = taskService;
        this.props = props;
        this.clock = clock;
    }

    @Override
    @Transactional
    public long create(String sessionId, String actionType, String payload, Long sourceMessageId) {
        String userId = currentUserId();
        if (sessionId == null || sessionId.isBlank()) {
            throw new BusinessException("sessionId 不能为空");
        }
        if (sessionId.length() > 64) {
            throw new BusinessException("sessionId 长度不能超过 64");
        }
        if (!"memory".equals(actionType) && !"task".equals(actionType) && !"mixed".equals(actionType)) {
            throw new BusinessException("actionType 只能是 memory/task/mixed");
        }
        if (payload == null || payload.isBlank()) {
            throw new BusinessException("待确认内容不能为空");
        }
        // payload 大小上限（防滥用）
        if (payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                > props.getPendingPayloadMaxBytes()) {
            throw new BusinessException("待确认内容过长");
        }
        // 不要把 confirm 必然拒绝的空/非法 proposal 写进 pending。
        ensureConfirmablePayload(payload);

        PendingAction action = new PendingAction();
        action.setUserId(userId);
        action.setSessionId(sessionId);
        action.setActionType(actionType);
        action.setPayload(payload);
        action.setSourceMessageId(sourceMessageId);
        action.setExpiresAt(LocalDateTime.now(clock).plusMinutes(props.getPendingTtlMinutes()));

        // 同一 user+session 旧 pending 先置 expired，再插新的（同事务，唯一键兜底并发）
        pendingActionMapper.expireSession(userId, sessionId);
        try {
            pendingActionMapper.insert(action);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 并发创建时 pending_slot 唯一键兜底；返回 409 让用户重试，不静默串提案
            log.warn("pending 并发插入冲突: userId={}, session={}", userId, sessionId);
            throw new BusinessException(409, "待确认动作正在更新，请重试");
        }
        if (action.getId() == null) {
            throw new BusinessException("待确认动作创建失败，请重试");
        }
        return action.getId();
    }

    @Override
    public PendingActionVO current(String sessionId) {
        String userId = currentUserId();
        PendingAction action = pendingActionMapper.selectPendingBySession(userId, sessionId);
        if (action == null) {
            return null;
        }
        // 懒过期：到点未确认 → 顺手置 expired，返回 null（上层让重新发起）
        LocalDateTime now = LocalDateTime.now(clock);
        if (action.getExpiresAt() != null && !action.getExpiresAt().isAfter(now)) {
            pendingActionMapper.markExpiredIfDue(action.getId(), userId, now);
            return null;
        }
        return toVO(action, now);
    }

    @Override
    public PendingActionVO getById(long id) {
        String userId = currentUserId();
        PendingAction action = pendingActionMapper.selectById(id, userId);
        if (action == null) {
            return null;
        }
        // 懒过期：pending 但已到期 → 顺手置 expired 返回 null（与 current 一致）
        LocalDateTime now = LocalDateTime.now(clock);
        if ("pending".equals(action.getStatus())
                && action.getExpiresAt() != null && !action.getExpiresAt().isAfter(now)) {
            pendingActionMapper.markExpiredIfDue(action.getId(), action.getUserId(), now);
            return null;
        }
        return toVO(action, now);
    }

    @Override
    @Transactional
    public ConfirmResult confirm(long id) {
        String userId = currentUserId();
        PendingAction action = pendingActionMapper.selectById(id, userId);
        if (action == null) {
            throw new BusinessException(404, "待确认动作不存在");
        }
        // 重复确认 → 幂等返回 alreadyConfirmed
        if ("confirmed".equals(action.getStatus())) {
            ConfirmResult already = new ConfirmResult(true, new ArrayList<>(), new ArrayList<>());
            already.setSessionId(action.getSessionId());
            return already;
        }
        // 已拒绝/已过期 → 409
        if ("rejected".equals(action.getStatus())) {
            throw new BusinessException(409, "该确认已被拒绝，请重新发起");
        }
        // 懒过期判断：pending 但已到期
        LocalDateTime now = LocalDateTime.now(clock);
        if ("pending".equals(action.getStatus())
                && action.getExpiresAt() != null && !action.getExpiresAt().isAfter(now)) {
            pendingActionMapper.markExpiredIfDue(action.getId(), action.getUserId(), now);
            throw new BusinessException(409, "该确认已过期，请重新发起");
        }
        if (!"pending".equals(action.getStatus())) {
            throw new BusinessException(409, "该确认状态已失效（" + action.getStatus() + "），请重新发起");
        }

        // 原子占位：只有从 pending 成功切到 confirmed 的线程才继续落库；并发/重复都进不来
        int rows = pendingActionMapper.markConfirmed(id, userId);
        if (rows == 0) {
            // 被并发抢先（已 confirmed/rejected/expired）
            PendingAction latest = pendingActionMapper.selectById(id, userId);
            if (latest != null && "confirmed".equals(latest.getStatus())) {
                ConfirmResult already = new ConfirmResult(true, new ArrayList<>(), new ArrayList<>());
                already.setSessionId(latest.getSessionId());
                return already;
            }
            throw new BusinessException(409, "该确认已失效，请刷新后重试");
        }

        // 成功抢到状态迁移后再校验 payload。这样并发重复确认会先走 alreadyConfirmed 幂等分支，
        // 而空/非法 payload 会抛异常让事务回滚，不会出现“确认成功但什么都没创建”。
        ensureConfirmablePayload(action.getPayload());

        // 按 payload 实际内容落库（memories + tasks，可能都为空则只确认不建）
        // P1.1 会话隔离：记忆按候选自带的 scope 决定落库 session（session 级 = 本会话，global = 全局）
        List<Map<String, Object>> memoryResults = applyMemories(action);
        List<Map<String, Object>> taskResults = applyTasks(action);
        log.info("pending 确认: userId={}, id={}, memories={}, tasks={}",
                userId, id, memoryResults.size(), taskResults.size());
        ConfirmResult result = new ConfirmResult(false, memoryResults, taskResults);
        result.setSessionId(action.getSessionId());

        // 聊天历史由聊天链路负责，PendingActionService 只负责状态机 + 业务落库。
        // 弹窗确认已经有 success 状态；如果这里再写一条 assistant 消息，
        // 前端刷新历史时会变成“确认提示 + 已设好”两条 AI 气泡，重复且噪音。
        return result;
    }

    @Override
    public boolean reject(long id) {
        String userId = currentUserId();
        PendingAction action = pendingActionMapper.selectById(id, userId);
        if (action == null) {
            throw new BusinessException(404, "待确认动作不存在");
        }
        // 幂等：已拒绝的重复 reject 静默成功
        if ("rejected".equals(action.getStatus())) {
            return false;
        }
        if ("confirmed".equals(action.getStatus())) {
            throw new BusinessException(409, "该确认已确认，不能再拒绝");
        }
        // 已过期等价于拒绝：允许用户点“取消”后拿到 200，不要把过期状态又报成 409。
        if ("expired".equals(action.getStatus())) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        if ("pending".equals(action.getStatus())
                && action.getExpiresAt() != null && !action.getExpiresAt().isAfter(now)) {
            pendingActionMapper.markExpiredIfDue(action.getId(), action.getUserId(), now);
            return false; // 过期等价于拒绝，静默
        }
        int rows = pendingActionMapper.markRejected(id, userId);
        if (rows == 0) {
            // 并发被确认/过期
            PendingAction latest = pendingActionMapper.selectById(id, userId);
            if (latest != null
                    && ("rejected".equals(latest.getStatus()) || "expired".equals(latest.getStatus()))) {
                return false; // 幂等：并发拒绝或并发过期都视为取消成功
            }
            throw new BusinessException(409, "该确认已失效，请刷新后重试");
        }
        return true;
    }

    /**
     * 确认前校验 payload：至少要有一条“可落库”的记忆/任务候选。
     * 解析失败、空数组、只有 P1 不支持的 update/delete 候选都直接 400，
     * 不能把 pending 置 confirmed 后返回“都帮你设好了”。
     */
    private void ensureConfirmablePayload(String payload) {
        boolean hasMemory = IntentPayloadCodec
                .parseCandidates(payload, "memories", MemoryCandidate.class).stream()
                .anyMatch(c -> c != null && c.getKey() != null && c.getValue() != null
                        && !c.getValue().isBlank());
        boolean hasTask = IntentPayloadCodec
                .parseCandidates(payload, "tasks", TaskCandidate.class).stream()
                .anyMatch(c -> c != null && c.getTitle() != null && !c.getTitle().isBlank()
                        && (c.getAction() == null || "create".equals(c.getAction())));
        if (!hasMemory && !hasTask) {
            throw new BusinessException("待确认内容为空或已失效，请重新发起");
        }
    }

    // 落库

    private List<Map<String, Object>> applyMemories(PendingAction action) {
        List<Map<String, Object>> results = new ArrayList<>();
        List<MemoryCandidate> memories = IntentPayloadCodec.parseCandidates(action.getPayload(), "memories",
                MemoryCandidate.class);
        for (MemoryCandidate c : memories) {
            if (c == null || c.getKey() == null || c.getValue() == null) {
                continue; // 跳过不合法候选，不阻断其它
            }
            // P1.1：会话级记忆（scope=session）只在本会话生效；global/缺省 = 全局画像
            String sessionScope = "session".equals(c.getScope()) ? action.getSessionId() : null;
            MemoryVO vo = memoryService.createWithReplaces(
                    c.getKey(), c.getValue(), c.getMemType(),
                    c.getConfidence(), c.getReplacesId(), action.getSourceMessageId(),
                    sessionScope);
            Map<String, Object> r = new HashMap<>();
            r.put("id", vo.getId());
            r.put("action", "created");
            r.put("key", vo.getMemKey());
            r.put("value", vo.getContent());
            results.add(r);
        }
        return results;
    }

    private List<Map<String, Object>> applyTasks(PendingAction action) {
        List<Map<String, Object>> results = new ArrayList<>();
        List<TaskCandidate> tasks = IntentPayloadCodec.parseCandidates(action.getPayload(), "tasks",
                TaskCandidate.class);
        for (TaskCandidate c : tasks) {
            if (c == null || c.getTitle() == null) {
                continue;
            }
            // P1 只允许 action=create（update/delete 留 P1.5）
            if (c.getAction() != null && !"create".equals(c.getAction())) {
                log.warn("跳过非 create 任务候选（P1 不支持）: action={}", c.getAction());
                continue;
            }
            CreateTaskRequest req = new CreateTaskRequest();
            req.setTitle(c.getTitle());
            req.setDueAt(c.getDueAt());
            req.setRecurrence(c.getRecurrence());
            req.setRecurrenceRule(c.getRecurrenceRule());
            // P1.5：记录任务从哪个会话发起（溯源），可见性仍全账号
            TaskVO vo = taskService.createFromPending(req, action.getSessionId());
            Map<String, Object> r = new HashMap<>();
            r.put("id", vo.getId());
            r.put("action", "created");
            r.put("title", vo.getTitle());
            r.put("dueAt", vo.getDueAt());
            r.put("recurrence", vo.getRecurrence());
            r.put("recurrenceRule", vo.getRecurrenceRule());
            results.add(r);
        }
        return results;
    }

    private PendingActionVO toVO(PendingAction action, LocalDateTime now) {
        PendingActionVO vo = new PendingActionVO();
        vo.setId(action.getId());
        vo.setActionType(action.getActionType());
        vo.setStatus(action.getStatus());
        vo.setSessionId(action.getSessionId());
        vo.setExpiresAt(action.getExpiresAt());
        vo.setMemories(IntentPayloadCodec.parseCandidates(
                action.getPayload(), "memories", MemoryCandidate.class));
        vo.setTasks(IntentPayloadCodec.parseCandidates(
                action.getPayload(), "tasks", TaskCandidate.class));
        vo.setPreview(buildPreview(vo.getMemories(), vo.getTasks()));
        return vo;
    }

    private String buildPreview(List<MemoryCandidate> memories, List<TaskCandidate> tasks) {
        StringBuilder sb = new StringBuilder("我识别到 ");
        int n = memories.size() + tasks.size();
        if (n == 0) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        if (!memories.isEmpty()) {
            parts.add(memories.size() + " 条记忆");
        }
        if (!tasks.isEmpty()) {
            parts.add(tasks.size() + " 个任务");
        }
        sb.append(String.join("、", parts)).append("，确认吗？");
        return sb.toString();
    }

    private String currentUserId() {
        UserDto user = UserHolder.getUser();
        if (user == null || user.getId() == null) {
            throw new BusinessException(401, "请先登录");
        }
        return user.getId();
    }
}
