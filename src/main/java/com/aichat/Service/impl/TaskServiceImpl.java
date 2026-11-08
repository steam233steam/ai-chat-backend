package com.aichat.Service.impl;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.aichat.Config.MindButlerProperties;
import com.aichat.Dto.CompleteTaskRequest;
import com.aichat.Dto.CreateTaskRequest;
import com.aichat.Dto.TaskRunVO;
import com.aichat.Dto.TaskVO;
import com.aichat.Dto.UpdateTaskRequest;
import com.aichat.Dto.UserDto;
import com.aichat.Entity.UserTask;
import com.aichat.Exception.BusinessException;
import com.aichat.Entity.TaskRun;
import com.aichat.Mapper.TaskRunMapper;
import com.aichat.Mapper.UserTaskMapper;
import com.aichat.Service.TaskService;
import com.aichat.utils.UserHolder;

@Service
public class TaskServiceImpl implements TaskService {
    private static final Logger log = LoggerFactory.getLogger(TaskServiceImpl.class);

    private final UserTaskMapper userTaskMapper;
    private final TaskRunMapper taskRunMapper;
    private final MindButlerProperties props;
    private final Clock clock;

    public TaskServiceImpl(UserTaskMapper userTaskMapper, TaskRunMapper taskRunMapper,
            MindButlerProperties props, Clock clock) {
        this.userTaskMapper = userTaskMapper;
        this.taskRunMapper = taskRunMapper;
        this.props = props;
        this.clock = clock;
    }

    @Override
    public List<TaskVO> list(String status, Long beforeId, int limit) {
        String userId = currentUserId();
        int cap = Math.max(1, Math.min(limit, 100));
        long cursor = (beforeId == null) ? Long.MAX_VALUE : beforeId;
        String st = normalizeListStatus(status);
        LocalDateTime now = nowLocal();

        // 游标语义：id < beforeId，倒序取 limit 条，调用方（本方法）再反转成升序
        List<UserTask> rows = userTaskMapper.selectByUserBeforeId(userId, st, cursor, cap);
        List<TaskVO> result = new ArrayList<>(rows.size());
        for (int i = rows.size() - 1; i >= 0; i--) {
            result.add(toVO(rows.get(i), now));
        }
        return result;
    }

    @Override
    public TaskVO get(long id) {
        String userId = currentUserId();
        UserTask t = userTaskMapper.selectById(id, userId);
        if (t == null) {
            return null;
        }
        return toVO(t, nowLocal());
    }

    @Override
    public TaskVO create(CreateTaskRequest request) {
        String userId = currentUserId();
        if (userTaskMapper.countByUser(userId) >= props.getTaskMaxPerUser()) {
            throw new BusinessException("任务数量已达上限（" + props.getTaskMaxPerUser() + " 条），请先删除旧任务");
        }
        String title = normalizeTitle(request.getTitle());
        rejectIfSensitive(title);
        if (request.getRawInput() != null) {
            rejectIfSensitive(request.getRawInput());
        }
        String recurrence = normalizeRecurrence(request.getRecurrence(), request.getDueAt());
        String rule = normalizeRule(request.getRecurrence(), request.getRecurrenceRule());
        LocalDateTime dueAt = normalizeDueAt(recurrence, rule, request.getDueAt());

        UserTask task = new UserTask();
        task.setUserId(userId);
        task.setTitle(title);
        task.setDueAt(dueAt);
        task.setTimezone(props.getTaskZone());
        task.setRecurrence(recurrence);
        task.setRecurrenceRule(rule);
        task.setRawInput(trimTo(request.getRawInput(), 512));
        task.setCreatedFromSession(request.getCreatedFromSession());
        applyAction(task, request.getActionType(), request.getActionConfig(), null);
        // status='pending'、version=0 由 Mapper insert 语句写死

        userTaskMapper.insert(task);
        // 回填 id + 查回完整行（含 create_time/update_time）
        UserTask saved = userTaskMapper.selectById(task.getId(), userId);
        if (saved == null) {
            throw new BusinessException("任务创建后查询失败，请重试");
        }
        return toVO(saved, nowLocal());
    }

    @Override
    public TaskVO update(long id, UpdateTaskRequest request) {
        String userId = currentUserId();
        if (request == null || request.getVersion() == null) {
            throw new BusinessException("version 不能为空");
        }
        UserTask current = userTaskMapper.selectById(id, userId);
        if (current == null) {
            return null; // 非本人或已软删，上层 404
        }
        if (!"pending".equals(current.getStatus())) {
            throw new BusinessException("只能修改 pending 状态的任务");
        }

        UserTask upd = new UserTask();
        upd.setId(id);
        upd.setUserId(userId);
        String newTitle = request.getTitle() != null ? normalizeTitle(request.getTitle()) : current.getTitle();
        rejectIfSensitive(newTitle);
        upd.setTitle(newTitle);
        upd.setTimezone(current.getTimezone());

        String newRecurrence = request.getRecurrence() != null
                ? normalizeRecurrence(request.getRecurrence(),
                        request.getDueAt() != null ? request.getDueAt() : current.getDueAt())
                : current.getRecurrence();
        String newRule = normalizeRule(newRecurrence,
                request.getRecurrenceRule() != null ? request.getRecurrenceRule() : current.getRecurrenceRule());
        boolean scheduleChanged = request.getDueAt() != null || request.getRecurrence() != null
                || request.getRecurrenceRule() != null;
        LocalDateTime newDue = request.getDueAt() != null ? request.getDueAt() : current.getDueAt();
        if (scheduleChanged) {
            newDue = normalizeDueAt(newRecurrence, newRule, newDue);
        }
        upd.setDueAt(newDue);
        upd.setRecurrence(newRecurrence);
        upd.setRecurrenceRule(newRule);
        upd.setStatus(normalizeStatus(request.getStatus(), current.getStatus()));
        applyAction(upd, request.getActionType(), request.getActionConfig(), current);
        upd.setVersion(request.getVersion());

        int rows = userTaskMapper.updateByIdAndVersion(upd);
        if (rows == 0) {
            throw new BusinessException(409, "版本冲突，请刷新后重试");
        }
        UserTask updated = userTaskMapper.selectById(id, userId);
        if (updated == null) {
            throw new BusinessException(404, "任务不存在");
        }
        return toVO(updated, nowLocal());
    }

    @Override
    public boolean delete(long id) {
        String userId = currentUserId();
        int rows = userTaskMapper.softDelete(id, userId);
        // 已是软删仍返回 true（幂等），只有不存在/非本人才返回 false → Controller 404。
        return rows > 0 || userTaskMapper.countById(id, userId) > 0;
    }

    @Override
    public TaskVO complete(long id, CompleteTaskRequest request) {
        String userId = currentUserId();
        if (request == null || request.getVersion() == null) {
            throw new BusinessException("version 不能为空");
        }
        UserTask current = userTaskMapper.selectById(id, userId);
        if (current == null) {
            return null;
        }
        if (!"pending".equals(current.getStatus())) {
            throw new BusinessException("只能完成 pending 状态的任务");
        }

        LocalDateTime now = nowLocal();
        String newStatus;
        LocalDateTime newDue;
        if ("once".equals(current.getRecurrence())) {
            newStatus = "done";
            // 保留原始截止时间，便于"已完成"列表展示/统计；不再清空 due_at
            newDue = current.getDueAt();
        } else {
            // recurring：保持 pending，due_at 从当前值滚动到下一次 > now
            newStatus = "pending";
            newDue = rollToNextDue(current, now);
        }

        int rows = userTaskMapper.complete(id, userId, newStatus, now, newDue, request.getVersion());
        if (rows == 0) {
            throw new BusinessException(409, "版本冲突，请刷新后重试");
        }
        log.info("任务完成: userId={}, taskId={}, recurrence={}, newStatus={}, newDue={}",
                userId, id, current.getRecurrence(), newStatus, newDue);
        return toVO(userTaskMapper.selectById(id, userId), now);
    }

    @Override
    public List<TaskVO> due(int limit) {
        String userId = currentUserId();
        int cap = Math.max(1, Math.min(limit, 100));
        LocalDateTime now = nowLocal();
        return userTaskMapper.selectDue(userId, now, cap).stream().map(t -> toVO(t, now)).toList();
    }

    @Override
    public TaskVO createFromPending(CreateTaskRequest request, String sourceSessionId) {
        request.setCreatedFromSession(sourceSessionId);
        return create(request);
    }

    @Override
    public List<TaskRunVO> runs(long id, int limit) {
        String userId = currentUserId();
        if (userTaskMapper.selectById(id, userId) == null) {
            throw new BusinessException(404, "任务不存在或已删除");
        }
        int cap = Math.max(1, Math.min(limit, 100));
        return taskRunMapper.selectByTask(id, userId, cap).stream().map(TaskServiceImpl::toRunVO).toList();
    }

    private static TaskRunVO toRunVO(TaskRun run) {
        return new TaskRunVO(run.getId(), run.getTaskId(), run.getActionType(), run.getTriggerType(),
                run.getStatus(), run.getDetail(), run.getStartedAt(), run.getFinishedAt());
    }

    // 内部辅助

    /**
     * recurring 任务的"下一次"滚动：
     * - daily: due_at + 1 天，循环推进到 > now
     * - weekly: due_at + recurrence_rule 指定星期，+ 7 天循环到 > now
     * 提前完成（now > due_at）也能正确滚到未来。
     */
    private LocalDateTime rollToNextDue(UserTask t, LocalDateTime now) {
        ZoneId zone = ZoneId.of(props.getTaskZone());
        ZonedDateTime start = t.getDueAt() == null
                ? ZonedDateTime.now(clock).withZoneSameInstant(zone)
                : t.getDueAt().atZone(zone);
        // 先把 due_at 推进至少一个周期（"提前完成"场景下也能滚到未来）
        ZonedDateTime cursor = "daily".equals(t.getRecurrence())
                ? start.plusDays(1)
                : start.plusWeeks(1);
        // 循环推进到 > now
        ZonedDateTime target = ZonedDateTime.now(clock).withZoneSameInstant(zone);
        while (!cursor.isAfter(target)) {
            cursor = "daily".equals(t.getRecurrence()) ? cursor.plusDays(1) : cursor.plusWeeks(1);
        }
        return cursor.toLocalDateTime();
    }

    private void rejectIfSensitive(String content) {
        String hit = com.aichat.utils.SensitiveContentFilter.detectSensitiveData(content);
        if (hit != null) {
            throw new BusinessException(hit + "，不会保存");
        }
    }

    private String normalizeTitle(String raw) {
        String t = com.aichat.utils.SensitiveContentFilter.stripControlChars(raw);
        if (t.isEmpty()) {
            throw new BusinessException("任务标题不能为空");
        }
        int len = t.codePointCount(0, t.length());
        if (len > 128) {
            throw new BusinessException("任务标题不能超过 128 字");
        }
        return t;
    }

    private String normalizeRecurrence(String raw, LocalDateTime dueAt) {
        String r = (raw == null || raw.isBlank()) ? "once" : raw.trim().toLowerCase(Locale.ROOT);
        if (!r.equals("once") && !r.equals("daily") && !r.equals("weekly")) {
            throw new BusinessException("P1 只支持 once/daily/weekly 重复");
        }
        // recurring 任务必须有截止时间
        if (!r.equals("once") && dueAt == null) {
            throw new BusinessException("daily/weekly 任务必须指定截止时间");
        }
        return r;
    }

    private String normalizeRule(String recurrence, String rawRule) {
        // null/blank 视为 once/daily（无需 rule）；normalizeRecurrence 已保证只产出 once/daily/weekly
        String rc = (recurrence == null || recurrence.isBlank()) ? "once" : recurrence.trim().toLowerCase();
        if ("once".equals(rc) || "daily".equals(rc)) {
            return null;
        }
        // weekly：必须带 BYDAY=XX
        if (rawRule == null || rawRule.isBlank()) {
            throw new BusinessException("weekly 任务必须指定 recurrenceRule（如 BYDAY=MO）");
        }
        String r = rawRule.trim().toUpperCase();
        if (!r.matches("^BYDAY=(MO|TU|WE|TH|FR|SA|SU)$")) {
            throw new BusinessException("recurrenceRule 格式应为 BYDAY=MO（周一）");
        }
        return r;
    }

    private String normalizeListStatus(String raw) {
        String s = (raw == null || raw.isBlank()) ? "pending" : raw.trim().toLowerCase(Locale.ROOT);
        if (!s.equals("pending") && !s.equals("done") && !s.equals("cancelled")) {
            throw new BusinessException("status 只允许 pending/done/cancelled");
        }
        return s;
    }

    private String normalizeStatus(String raw, String fallback) {
        String s = (raw == null || raw.isBlank()) ? fallback : raw.trim().toLowerCase(Locale.ROOT);
        if (!s.equals("pending") && !s.equals("cancelled")) {
            throw new BusinessException("status 只允许 pending/cancelled（done 走 complete 接口）");
        }
        return s;
    }

    /**
     * 校验并归一化 due_at：
     * - recurring（daily/weekly）必须有截止时间，且不能是过去（从下次周期开始）
     * - once 允许 null（"记得买牛奶"无时间）；过去时间允许（补录场景，前端展示为 overdue）
     * P1 固定 Asia/Shanghai，前端直接传 LocalDateTime 即可。
     */
    private LocalDateTime normalizeDueAt(String recurrence, String rule, LocalDateTime dueAt) {
        if ("once".equals(recurrence)) {
            return dueAt; // 可空；过去时间允许（补录场景，前端展示为 overdue）
        }
        // recurring：必须有截止时间，并从解析结果推进到下一次 > now
        if (dueAt == null) {
            throw new BusinessException("daily/weekly 任务必须指定截止时间");
        }
        return rollForward(recurrence, rule, dueAt, nowLocal());
    }

    /** 把 recurring 的 due_at 从解析结果推进到 > now；weekly 按 BYDAY 对齐。 */
    private LocalDateTime rollForward(String recurrence, String rule, LocalDateTime dueAt, LocalDateTime now) {
        ZoneId zone = ZoneId.of(props.getTaskZone());
        ZonedDateTime cursor = dueAt.atZone(zone);
        ZonedDateTime target = now.atZone(zone);
        if ("weekly".equals(recurrence)) {
            DayOfWeek day = parseByDay(rule);
            while (!cursor.getDayOfWeek().equals(day) || !cursor.isAfter(target)) {
                cursor = cursor.plusDays(1);
            }
            return cursor.toLocalDateTime();
        }
        while (!cursor.isAfter(target)) {
            cursor = cursor.plusDays(1);
        }
        return cursor.toLocalDateTime();
    }

    private DayOfWeek parseByDay(String rule) {
        if (rule == null || !rule.startsWith("BYDAY=")) {
            throw new BusinessException("weekly 任务必须指定 recurrenceRule（如 BYDAY=MO）");
        }
        return switch (rule.substring("BYDAY=".length())) {
            case "MO" -> DayOfWeek.MONDAY;
            case "TU" -> DayOfWeek.TUESDAY;
            case "WE" -> DayOfWeek.WEDNESDAY;
            case "TH" -> DayOfWeek.THURSDAY;
            case "FR" -> DayOfWeek.FRIDAY;
            case "SA" -> DayOfWeek.SATURDAY;
            case "SU" -> DayOfWeek.SUNDAY;
            default -> throw new BusinessException("recurrenceRule 格式应为 BYDAY=MO（周一）");
        };
    }

    private String trimTo(String s, int max) {
        if (s == null) {
            return null;
        }
        int cp = s.codePointCount(0, s.length());
        if (cp <= max) {
            return s;
        }
        int end = s.offsetByCodePoints(0, max);
        return s.substring(0, end);
    }

    private static final java.util.regex.Pattern WEBHOOK_URL =
            java.util.regex.Pattern.compile("\"url\"\\s*:\\s*\"([^\"]+)\"");

    /**
     * 到点做什么：notify（站内提醒）/ webhook（回调地址）。
     * create 时 current 为 null；update 时未传 actionType 就沿用旧值（partial update 语义）。
     */
    private void applyAction(UserTask task, String actionType, String actionConfig, UserTask current) {
        String type = actionType != null ? actionType.trim().toLowerCase()
                : (current != null ? current.getActionType() : null);
        if (type == null || type.isEmpty()) {
            type = "notify";
        }
        if (!"notify".equals(type) && !"webhook".equals(type)) {
            throw new BusinessException("动作类型只支持 notify / webhook");
        }
        String config = actionConfig != null ? actionConfig.trim()
                : (actionType == null && current != null ? current.getActionConfig() : null);
        if ("webhook".equals(type)) {
            if (config == null || config.isEmpty()) {
                throw new BusinessException("webhook 动作需要填写 {\"url\":\"https://...\"}");
            }
            java.util.regex.Matcher m = WEBHOOK_URL.matcher(config);
            if (!m.find()) {
                throw new BusinessException("webhook 参数里缺少 url");
            }
            String url = m.group(1).trim();
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                throw new BusinessException("webhook 地址必须以 http:// 或 https:// 开头");
            }
        } else {
            config = null;
        }
        task.setActionType(type);
        task.setActionConfig(config == null || config.isEmpty() ? null : trimTo(config, 1024));
    }

    private String currentUserId() {
        UserDto user = UserHolder.getUser();
        if (user == null || user.getId() == null) {
            throw new BusinessException(401, "请先登录");
        }
        return user.getId();
    }

    private LocalDateTime nowLocal() {
        return ZonedDateTime.now(clock).withZoneSameInstant(ZoneId.of(props.getTaskZone())).toLocalDateTime();
    }

    private TaskVO toVO(UserTask t, LocalDateTime now) {
        TaskVO vo = new TaskVO();
        vo.setId(t.getId());
        vo.setTitle(t.getTitle());
        vo.setDueAt(t.getDueAt());
        vo.setTimezone(t.getTimezone());
        vo.setRecurrence(t.getRecurrence());
        vo.setRecurrenceRule(t.getRecurrenceRule());
        vo.setStatus(t.getStatus());
        vo.setDeletedAt(t.getDeletedAt());
        vo.setCompletedAt(t.getCompletedAt());
        vo.setRawInput(t.getRawInput());
        vo.setVersion(t.getVersion());
        vo.setCreatedFromSession(t.getCreatedFromSession());
        vo.setActionType(t.getActionType() == null ? "notify" : t.getActionType());
        vo.setActionConfig(t.getActionConfig());
        vo.setCreateTime(t.getCreateTime());
        vo.setUpdateTime(t.getUpdateTime());
        vo.setOverdue(t.getDueAt() != null && !t.getDueAt().isAfter(now)
                && "pending".equals(t.getStatus()));
        return vo;
    }
}
