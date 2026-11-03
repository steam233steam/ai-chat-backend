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
import com.aichat.utils.UserHolder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

@Service
public class PendingActionServiceimpl implements PendingActionService {
    private static final Logger log = LoggerFactory.getLogger(PendingActionServiceimpl.class);

    @Autowired
    private PendingActionMapper pendingActionMapper;
    @Autowired
    private MemoryService memoryService;
    @Autowired
    private TaskService taskService;
    @Autowired
    private MindButlerProperties props;
    @Autowired
    private Clock clock;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Override
    @Transactional
    public long create(String sessionId, String actionType, String payload, Long sourceMessageId) {
        String userId = currentUserId();
        // payload 大小上限（防滥用）
        if (payload != null && payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                > props.getPendingPayloadMaxBytes()) {
            throw new BusinessException("待确认内容过长");
        }

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
            pendingActionMapper.expireSession(userId, sessionId);
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
            return new ConfirmResult(true, new ArrayList<>(), new ArrayList<>());
        }
        // 已拒绝/已过期 → 409
        if ("rejected".equals(action.getStatus())) {
            throw new BusinessException(409, "该确认已被拒绝，请重新发起");
        }
        // 懒过期判断：pending 但已到期
        LocalDateTime now = LocalDateTime.now(clock);
        if ("pending".equals(action.getStatus())
                && action.getExpiresAt() != null && !action.getExpiresAt().isAfter(now)) {
            pendingActionMapper.expireSession(action.getUserId(), action.getSessionId());
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
                return new ConfirmResult(true, new ArrayList<>(), new ArrayList<>());
            }
            throw new BusinessException(409, "该确认已失效，请刷新后重试");
        }

        // 按 payload 实际内容落库（memories + tasks，可能都为空则只确认不建）
        List<Map<String, Object>> memoryResults = applyMemories(action);
        List<Map<String, Object>> taskResults = applyTasks(action);
        log.info("pending 确认: userId={}, id={}, memories={}, tasks={}",
                userId, id, memoryResults.size(), taskResults.size());
        return new ConfirmResult(false, memoryResults, taskResults);
    }

    @Override
    public void reject(long id) {
        String userId = currentUserId();
        PendingAction action = pendingActionMapper.selectById(id, userId);
        if (action == null) {
            throw new BusinessException(404, "待确认动作不存在");
        }
        // 幂等：已拒绝的重复 reject 静默成功
        if ("rejected".equals(action.getStatus())) {
            return;
        }
        if ("confirmed".equals(action.getStatus())) {
            throw new BusinessException(409, "该确认已确认，不能再拒绝");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        if ("pending".equals(action.getStatus())
                && action.getExpiresAt() != null && !action.getExpiresAt().isAfter(now)) {
            pendingActionMapper.expireSession(action.getUserId(), action.getSessionId());
            return; // 过期等价于拒绝，静默
        }
        int rows = pendingActionMapper.markRejected(id, userId);
        if (rows == 0) {
            // 并发被确认/过期
            PendingAction latest = pendingActionMapper.selectById(id, userId);
            if (latest != null && "rejected".equals(latest.getStatus())) {
                return; // 幂等
            }
            throw new BusinessException(409, "该确认已失效，请刷新后重试");
        }
    }

    // ---------- 落库 ----------

    private List<Map<String, Object>> applyMemories(PendingAction action) {
        List<Map<String, Object>> results = new ArrayList<>();
        List<MemoryCandidate> memories = parseCandidates(action.getPayload(), "memories",
                MemoryCandidate.class);
        for (MemoryCandidate c : memories) {
            if (c == null || c.getKey() == null || c.getValue() == null) {
                continue; // 跳过不合法候选，不阻断其它
            }
            MemoryVO vo = memoryService.createWithReplaces(
                    c.getKey(), c.getValue(), c.getMemType(),
                    c.getConfidence(), c.getReplacesId(), action.getSourceMessageId());
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
        List<TaskCandidate> tasks = parseCandidates(action.getPayload(), "tasks",
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
            TaskVO vo = taskService.create(req);
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

    /** 从 payload JSON 的指定数组字段解析候选列表（解析失败返回空，不抛）。 */
    private <T> List<T> parseCandidates(String payload, String field, Class<T> type) {
        List<T> list = new ArrayList<>();
        if (payload == null || payload.isBlank()) {
            return list;
        }
        try {
            JsonNode root = objectMapper.readTree(payload);
            JsonNode arr = root.get(field);
            if (arr == null || !arr.isArray()) {
                return list;
            }
            for (JsonNode node : arr) {
                list.add(objectMapper.convertValue(node, type));
            }
        } catch (Exception e) {
            log.warn("解析 pending payload 失败，按空处理: {}", e.getMessage());
        }
        return list;
    }

    private PendingActionVO toVO(PendingAction action, LocalDateTime now) {
        PendingActionVO vo = new PendingActionVO();
        vo.setId(action.getId());
        vo.setActionType(action.getActionType());
        vo.setStatus(action.getStatus());
        vo.setExpiresAt(action.getExpiresAt());
        vo.setMemories(parseCandidates(action.getPayload(), "memories", MemoryCandidate.class));
        vo.setTasks(parseCandidates(action.getPayload(), "tasks", TaskCandidate.class));
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
