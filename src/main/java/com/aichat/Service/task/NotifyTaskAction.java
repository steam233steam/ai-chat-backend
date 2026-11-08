package com.aichat.Service.task;

import java.time.LocalDateTime;

import org.springframework.stereotype.Component;

import com.aichat.Entity.UserTask;

/**
 * 默认动作：站内提醒。
 *
 * 本身不往外发东西——前端轮询 /user/tasks/due 就能看到到点任务，这里只负责"被算作响过一次"，
 * 记一条执行记录。真要做邮件/QQ，加一个 TaskAction 实现即可，不用动调度。
 */
@Component
public class NotifyTaskAction implements TaskAction {

    @Override
    public String name() {
        return "notify";
    }

    @Override
    public String run(UserTask task, LocalDateTime now) {
        return "站内提醒：" + task.getTitle();
    }
}
