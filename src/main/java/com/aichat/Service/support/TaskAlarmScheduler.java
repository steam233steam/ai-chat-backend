package com.aichat.Service.support;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.aichat.Entity.TaskRun;
import com.aichat.Entity.UserTask;
import com.aichat.Mapper.TaskRunMapper;
import com.aichat.Mapper.UserTaskMapper;
import com.aichat.Service.task.TaskAction;

import jakarta.annotation.PostConstruct;

/**
 * 任务"闹钟"：每分钟扫一次库里到点的任务，执行它配置的动作，写一条执行记录。
 *
 * 几条约定：
 * - 防重复：last_notified_at 记"这一轮响过"，条件里用 last_notified_at &lt; due_at 判定；重复任务在
 *   完成时会把 last_notified_at 清空并滚动 due_at，所以下一轮还会响。
 * - 防并发：多实例部署时用 Redis 锁；Redis 不可用则退化为"无锁执行"，宁可重复一次也不漏。
 * - 失败隔离：单条任务失败只记 failed，不影响这一批其它任务。
 */
@Component
public class TaskAlarmScheduler {

    private static final Logger log = LoggerFactory.getLogger(TaskAlarmScheduler.class);

    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final UserTaskMapper userTaskMapper;
    private final TaskRunMapper taskRunMapper;
    private final StringRedisTemplate redis;
    private final java.time.Clock clock;
    private final Map<String, TaskAction> actions = new HashMap<>();

    @Value("${app.task.alarm.enabled:true}")
    private boolean enabled;

    @Value("${app.task.alarm.batch:50}")
    private int batch;

    @Value("${app.task.alarm.lock-key:aichat:task:alarm:lock}")
    private String lockKey;

    @Value("${app.task.alarm.lock-seconds:50}")
    private long lockSeconds;

    public TaskAlarmScheduler(UserTaskMapper userTaskMapper,
            TaskRunMapper taskRunMapper,
            StringRedisTemplate redis,
            java.time.Clock clock,
            List<TaskAction> actionBeans) {
        this.userTaskMapper = userTaskMapper;
        this.taskRunMapper = taskRunMapper;
        this.redis = redis;
        this.clock = clock;
        if (actionBeans != null) {
            for (TaskAction action : actionBeans) {
                this.actions.put(action.name(), action);
            }
        }
    }

    @PostConstruct
    void logReady() {
        log.info("任务闹钟已装配，动作={}，enabled={}", actions.keySet(), enabled);
    }

    @Scheduled(fixedDelayString = "${app.task.alarm.interval-ms:60000}",
            initialDelayString = "${app.task.alarm.initial-delay-ms:20000}")
    public void tick() {
        if (!enabled) {
            return;
        }
        String token = UUID.randomUUID().toString();
        boolean locked = acquire(token);
        if (!locked) {
            log.debug("任务闹钟：上一轮还没结束，跳过");
            return;
        }
        try {
            runOnce();
        } catch (Exception e) {
            log.warn("任务闹钟执行异常：{}", e.getMessage());
        } finally {
            release(token);
        }
    }

    /** 扫一遍到期任务；返回处理条数（测试直接调）。 */
    public int runOnce() {
        LocalDateTime now = LocalDateTime.now(clock);
        List<UserTask> due = userTaskMapper.selectDueAll(now, Math.max(1, batch));
        int handled = 0;
        for (UserTask task : due) {
            handled++;
            fire(task, now);
        }
        if (handled > 0) {
            log.info("任务闹钟本轮处理 {} 条", handled);
        }
        return handled;
    }

    private void fire(UserTask task, LocalDateTime now) {
        String actionType = task.getActionType() == null || task.getActionType().isBlank()
                ? "notify"
                : task.getActionType();

        TaskRun run = new TaskRun();
        run.setTaskId(task.getId());
        run.setUserId(task.getUserId());
        run.setActionType(actionType);
        run.setTriggerType("scheduled");
        run.setStartedAt(now);

        try {
            TaskAction action = actions.get(actionType);
            if (action == null) {
                throw new IllegalStateException("未注册的动作类型：" + actionType);
            }
            String detail = action.run(task, now);
            run.setStatus("success");
            run.setDetail(trim(detail));
        } catch (Exception e) {
            run.setStatus("failed");
            run.setDetail(trim(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            log.warn("任务 {} 执行失败：{}", task.getId(), run.getDetail());
        }

        run.setFinishedAt(LocalDateTime.now(clock));
        try {
            taskRunMapper.insert(run);
            userTaskMapper.markNotified(task.getId(), now);
        } catch (Exception e) {
            log.warn("任务 {} 写执行记录失败：{}", task.getId(), e.getMessage());
        }
    }

    private boolean acquire(String token) {
        try {
            Boolean ok = redis.opsForValue().setIfAbsent(lockKey, token, Duration.ofSeconds(Math.max(5, lockSeconds)));
            return Boolean.TRUE.equals(ok);
        } catch (Exception e) {
            log.warn("任务闹钟取锁失败，降级为无锁执行：{}", e.getMessage());
            return true;
        }
    }

    private void release(String token) {
        try {
            redis.execute(RELEASE_SCRIPT, java.util.Collections.singletonList(lockKey), token);
        } catch (Exception e) {
            log.debug("任务闹钟释放锁失败：{}", e.getMessage());
        }
    }

    private String trim(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= 500 ? s : s.substring(0, 500);
    }
}
