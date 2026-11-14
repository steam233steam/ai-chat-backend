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
import com.aichat.Dto.UpdateMemoryRequest;
import com.aichat.Dto.MemoryChange;
import com.aichat.Dto.MemoryVO;
import com.aichat.Dto.PendingActionVO;
import com.aichat.Dto.TaskCandidate;
import com.aichat.Dto.TaskVO;
import com.aichat.Dto.UserDto;
import com.aichat.Entity.PendingAction;
import com.aichat.Exception.BusinessException;
import com.aichat.Mapper.PendingActionMapper;
import com.aichat.mcp.McpToolCatalog;
import com.aichat.Service.MemoryService;
import com.aichat.Service.PendingActionService;
import com.aichat.Service.TaskService;
import com.aichat.utils.IntentPayloadCodec;
import com.aichat.utils.UserHolder;

@Service
public class PendingActionServiceImpl implements PendingActionService {
    private static final Logger log = LoggerFactory.getLogger(PendingActionServiceImpl.class);

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private final PendingActionMapper pendingActionMapper;
    private final MemoryService memoryService;
    private final TaskService taskService;
    private final MindButlerProperties props;
    private final Clock clock;

    /** MCP 工具目录（可选：单测里没有 Spring 容器时为 null，退回旧的前缀拼法）。 */
    private McpToolCatalog mcpToolCatalog;

    @Autowired(required = false)
    void setMcpToolCatalog(McpToolCatalog mcpToolCatalog) {
        this.mcpToolCatalog = mcpToolCatalog;
    }
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
        if (!"memory".equals(actionType) && !"task".equals(actionType) && !"mixed".equals(actionType)
                && !"memory_change".equals(actionType)) {
            throw new BusinessException("actionType 只能是 memory/task/mixed/memory_change");
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
        ensureConfirmablePayload(actionType, payload);

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
    public String payloadOf(long id) {
        String userId = currentUserId();
        PendingAction action = pendingActionMapper.selectById(id, userId);
        return action == null ? null : action.getPayload();
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
        List<Map<String, Object>> memoryResults = new ArrayList<>();
        List<Map<String, Object>> taskResults = new ArrayList<>();
        String receipt = null;
        if ("memory_change".equals(action.getActionType())) {
            // 改/删走另一条落库路径：payload 里是 changes，不是 memories/tasks
            ChangeApplyResult applied = applyChanges(action);
            memoryResults = applied.memories();
            receipt = applied.receipt();
        } else {
            ensureConfirmablePayload(action.getActionType(), action.getPayload());

            // 按 payload 实际内容落库（memories + tasks，可能都为空则只确认不建）
            // P1.1 会话隔离：记忆按候选自带的 scope 决定落库 session（session 级 = 本会话，global = 全局）
            memoryResults = applyMemories(action);
            taskResults = applyTasks(action);
        }
        log.info("pending 确认: userId={}, id={}, memories={}, tasks={}",
                userId, id, memoryResults.size(), taskResults.size());
        ConfirmResult result = new ConfirmResult(false, memoryResults, taskResults);
        result.setSessionId(action.getSessionId());
        result.setReceipt(receipt);

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
    private void ensureConfirmablePayload(String actionType, String payload) {
        if ("memory_change".equals(actionType)) {
            boolean hasChange = IntentPayloadCodec.parseChanges(payload).stream()
                    .anyMatch(c -> c != null && c.getId() != null
                            && ("DELETE".equalsIgnoreCase(c.getAction())
                                    || "UPDATE".equalsIgnoreCase(c.getAction())));
            if (!hasChange) {
                throw new BusinessException("待确认内容为空或已失效，请重新发起");
            }
            return;
        }
        boolean hasMemory = IntentPayloadCodec
                .parseCandidates(payload, "memories", MemoryCandidate.class).stream()
                .anyMatch(c -> c != null && c.getKey() != null && c.getValue() != null
                        && !c.getValue().isBlank());
        boolean hasTask = IntentPayloadCodec
                .parseCandidates(payload, "tasks", TaskCandidate.class).stream()
                .anyMatch(c -> c != null && c.getTitle() != null && !c.getTitle().isBlank()
                        && (c.getAction() == null || "create".equals(c.getAction())
                            || ("mcp".equals(c.getAction()) && c.getTool() != null)));
        if (!hasMemory && !hasTask) {
            throw new BusinessException("待确认内容为空或已失效，请重新发起");
        }
    }

    // 落库

    @Override
    public void saveMemoriesQuietly(String sessionId, List<MemoryCandidate> memories, Long sourceMessageId) {
        if (memories == null || memories.isEmpty()) {
            return;
        }
        int saved = 0;
        for (MemoryCandidate c : memories) {
            if (c == null || c.getKey() == null || c.getValue() == null) {
                continue;
            }
            try {
                String sessionScope = "session".equals(c.getScope()) ? sessionId : null;
                memoryService.createWithReplaces(c.getKey(), c.getValue(), c.getMemType(),
                        c.getConfidence(), c.getReplacesId(), sourceMessageId, sessionScope);
                saved++;
            } catch (RuntimeException e) {
                // 静默保存失败不影响这一轮聊天
                log.warn("静默保存记忆失败（key={}）: {}", c.getKey(), e.getMessage());
            }
        }
        log.info("静默保存记忆 {} 条: session={}", saved, sessionId);
    }

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
            // 只支持 create（到点提醒）与 mcp（到点查一次）
            boolean isMcp = "mcp".equals(c.getAction()) && c.getTool() != null && !c.getTool().isBlank();
            if (c.getAction() != null && !"create".equals(c.getAction()) && !isMcp) {
                log.warn("跳过不支持的任务候选（P1 只有 create/mcp）: action={}", c.getAction());
                continue;
            }
            CreateTaskRequest req = new CreateTaskRequest();
            req.setTitle(c.getTitle());
            // 「查一下某事的现状」这类没有明确时间：默认一分钟后查一次，别让用户白等
            req.setDueAt(c.getDueAt() != null ? c.getDueAt()
                    : (isMcp ? LocalDateTime.now().plusMinutes(1) : null));
            req.setRecurrence(c.getRecurrence());
            req.setRecurrenceRule(c.getRecurrenceRule());
            // AI 想让它到点去查东西（天气/搜索/时间/抓网页）时，带上 MCP 动作
            if (c.getTool() != null && !c.getTool().isBlank()) {
                req.setActionType("mcp");
                req.setActionConfig(buildMcpActionConfig(c.getTool(), c.getToolArgs()));
            }
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

    /**
     * 把模型给的短工具名解析成 MCP 目录里的公共名，再拼成调度器认的 action_config。
     *
     * <p>解析顺序：完全等于公共名 → 完全等于服务端原始工具名 → 公共名以 «_短名» 结尾。
     * 都匹配不上才退回旧约定（mcp_info_前缀），这样新增一个 MCP 服务器不用改这里。
     */
    private String buildMcpActionConfig(String tool, Map<String, Object> toolArgs) {
        Map<String, Object> config = new HashMap<>();
        config.put("tool", resolveMcpToolName(tool));
        config.put("args", toolArgs == null ? Map.of() : toolArgs);
        try {
            return MAPPER.writeValueAsString(config);
        } catch (Exception e) {
            log.warn("拼 MCP 动作参数失败，降级为站内提醒: {}", e.getMessage());
            return null;
        }
    }

    String resolveMcpToolName(String tool) {
        if (tool == null || tool.isBlank()) {
            return "mcp_info_" + tool;
        }
        if (mcpToolCatalog != null) {
            try {
                mcpToolCatalog.ensureStarted();
                var infos = mcpToolCatalog.toolInfos();
                for (McpToolCatalog.McpToolInfo info : infos) {
                    if (tool.equals(info.name())) {
                        return info.name();
                    }
                }
                for (McpToolCatalog.McpToolInfo info : infos) {
                    if (tool.equals(info.tool())) {
                        return info.name();
                    }
                }
                for (McpToolCatalog.McpToolInfo info : infos) {
                    if (info.name().endsWith("_" + tool)) {
                        return info.name();
                    }
                }
            } catch (RuntimeException e) {
                log.warn("解析 MCP 工具名失败，退回默认前缀: {}", e.getMessage());
            }
        }
        return "mcp_info_" + tool;
    }

    /** 变更落库结果：memories 里放每个操作的摘要（供前端/日志看），receipt 是回给用户的一句话。 */    private record ChangeApplyResult(List<Map<String, Object>> memories, String receipt) {
    }

    /**
     * 执行记忆的改/删。逐条处理：单条失败（例如已被别处删掉）只跳过并记日志，
     * 但乐观锁冲突要抛出去 —— 那说明用户看到的内容已经变了，不能悄悄覆盖。
     */
    private ChangeApplyResult applyChanges(PendingAction action) {
        List<Map<String, Object>> results = new ArrayList<>();
        List<String> said = new ArrayList<>();
        for (MemoryChange change : IntentPayloadCodec.parseChanges(action.getPayload())) {
            if (change == null || change.getId() == null || change.getAction() == null) {
                continue;
            }
            boolean delete = "DELETE".equalsIgnoreCase(change.getAction());
            MemoryVO current = memoryService.get(change.getId());
            if (current == null) {
                log.info("变更跳过：记忆已不存在, id={}", change.getId());
                continue;
            }
            Map<String, Object> row = new HashMap<>();
            row.put("id", current.getId());
            row.put("memKey", current.getMemKey());
            row.put("before", current.getContent());
            if (delete) {
                memoryService.delete(current.getId());
                row.put("action", "deleted");
                results.add(row);
                said.add("已删掉「" + current.getContent() + "」");
                continue;
            }
            UpdateMemoryRequest request = new UpdateMemoryRequest();
            request.setContent(change.getAfter());
            request.setVersion(current.getVersion());
            MemoryVO updated = memoryService.update(current.getId(), request);
            row.put("action", "updated");
            row.put("after", updated.getContent());
            results.add(row);
            said.add("「" + current.getContent() + "」改成「" + updated.getContent() + "」");
        }
        String receipt = said.isEmpty() ? "这几条记忆已经不在了，没有改动。" : String.join("；", said);
        return new ChangeApplyResult(results, receipt);
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
        if ("memory_change".equals(action.getActionType())) {
            // 变更提案没有"新建几条"的概念，文案直接用 payload 里生成好的那句话
            vo.setPreview(IntentPayloadCodec.parsePreview(action.getPayload()));
        } else {
            vo.setPreview(buildPreview(vo.getMemories(), vo.getTasks()));
        }
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
