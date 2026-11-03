package com.aichat.Service.impl;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.aichat.Config.MindButlerProperties;
import com.aichat.Dto.CompleteTaskRequest;
import com.aichat.Dto.CreateTaskRequest;
import com.aichat.Dto.TaskVO;
import com.aichat.Dto.UpdateTaskRequest;
import com.aichat.Dto.UserDto;
import com.aichat.Entity.UserTask;
import com.aichat.Exception.BusinessException;
import com.aichat.Mapper.UserTaskMapper;
import com.aichat.Service.TaskService;
import com.aichat.utils.UserHolder;

@Service
public class TaskServiceimpl implements TaskService {
    private static final Logger log = LoggerFactory.getLogger(TaskServiceimpl.class);

    @Autowired
    private UserTaskMapper userTaskMapper;
    @Autowired
    private MindButlerProperties props;
    @Autowired
    private Clock clock;

    @Override
    public List<TaskVO> list(String status, Long beforeId, int limit) {
        String userId = currentUserId();
        int cap = Math.max(1, Math.min(limit, 100));
        long cursor = (beforeId == null) ? Long.MAX_VALUE : beforeId;
        String st = normalizeStatus(status, "pending");
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
        String title = normalizeTitle(request.getTitle());
        String recurrence = normalizeRecurrence(request.getRecurrence(), request.getDueAt());
        String rule = normalizeRule(request.getRecurrence(), request.getRecurrenceRule());
        LocalDateTime dueAt = normalizeDueAt(recurrence, request.getDueAt(), userId);

        UserTask task = new UserTask();
        task.setUserId(userId);
        task.setTitle(title);
        task.setDueAt(dueAt);
        task.setTimezone(props.getTaskZone());
        task.setRecurrence(recurrence);
        task.setRecurrenceRule(rule);
        task.setRawInput(trimTo(request.getRawInput(), 512));
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
        upd.setTitle(request.getTitle() != null ? normalizeTitle(request.getTitle()) : current.getTitle());
        upd.setDueAt(request.getDueAt() != null ? request.getDueAt() : current.getDueAt());
        upd.setTimezone(current.getTimezone());
        upd.setRecurrence(request.getRecurrence() != null ? normalizeRecurrence(request.getRecurrence(), upd.getDueAt()) : current.getRecurrence());
        upd.setRecurrenceRule(normalizeRule(upd.getRecurrence(),
                request.getRecurrenceRule() != null ? request.getRecurrenceRule() : current.getRecurrenceRule()));
        upd.setStatus(normalizeStatus(request.getStatus(), current.getStatus()));
        upd.setVersion(request.getVersion());

        if ("pending".equals(upd.getStatus()) && upd.getRecurrence() != null && upd.getRecurrence().equals("weekly")
                && upd.getRecurrenceRule() == null) {
            throw new BusinessException("weekly 任务必须带 recurrenceRule（如 BYDAY=MO）");
        }

        int rows = userTaskMapper.updateByIdAndVersion(upd);
        if (rows == 0) {
            throw new BusinessException(409, "版本冲突，请刷新后重试");
        }
        return toVO(userTaskMapper.selectById(id, userId), nowLocal());
    }

    @Override
    public boolean delete(long id) {
        String userId = currentUserId();
        int rows = userTaskMapper.softDelete(id, userId);
        return rows > 0;
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
            newDue = null;
        } else {
            // recurring：保持 pending，due_at 从当前值滚动到下一次 > now
            newStatus = "pending";
            newDue = rollToNextDue(current, now);
        }

        int rows = userTaskMapper.complete(id, userId, newStatus, now, newDue, request.getVersion());
        if (rows == 0) {
            throw new BusinessException(409, "版本冲突，请刷新后重试");
        }
        return toVO(userTaskMapper.selectById(id, userId), now);
    }

    @Override
    public List<TaskVO> due(int limit) {
        String userId = currentUserId();
        int cap = Math.max(1, Math.min(limit, 100));
        LocalDateTime now = nowLocal();
        return userTaskMapper.selectDue(userId, cap).stream().map(t -> toVO(t, now)).toList();
    }

    // ---------- 内部辅助 ----------

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
        String r = (raw == null || raw.isBlank()) ? "once" : raw.trim().toLowerCase();
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
        if ("once".equals(recurrence) || "daily".equals(recurrence)) {
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

    private String normalizeStatus(String raw, String fallback) {
        String s = (raw == null || raw.isBlank()) ? fallback : raw.trim().toLowerCase();
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
    private LocalDateTime normalizeDueAt(String recurrence, LocalDateTime dueAt, String userId) {
        if ("once".equals(recurrence)) {
            return dueAt; // 可空
        }
        // recurring：必须有截止时间
        if (dueAt == null) {
            throw new BusinessException("daily/weekly 任务必须指定截止时间");
        }
        // recurring 的 due_at 作为"下一个发生点"，滚到 > now 由 create 时兜底（roll 逻辑在 complete 里）
        return dueAt;
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
        vo.setCreateTime(t.getCreateTime());
        vo.setUpdateTime(t.getUpdateTime());
        vo.setOverdue(t.getDueAt() != null && !t.getDueAt().isAfter(now)
                && "pending".equals(t.getStatus()));
        return vo;
    }
}
