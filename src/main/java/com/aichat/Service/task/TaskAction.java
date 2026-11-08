package com.aichat.Service.task;

import java.time.LocalDateTime;

import com.aichat.Entity.UserTask;

/**
 * 任务到点后要执行的动作。
 *
 * 闹钟内核里，"时间规则"和"触发后干什么"是分开的：新增动作只要实现本接口并注册成 Bean，
 * 任务表的 action_type 填它的 name() 即可，调度器不用改。
 */
public interface TaskAction {

    /** 动作标识，对应 tb_user_task.action_type。 */
    String name();

    /**
     * 执行动作。
     *
     * @return 写进 tb_task_run.detail 的一行摘要
     * @throws Exception 失败时抛出，调度器会记 status=failed 并继续处理下一条任务
     */
    String run(UserTask task, LocalDateTime now) throws Exception;
}
