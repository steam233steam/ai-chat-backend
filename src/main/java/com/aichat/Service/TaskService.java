package com.aichat.Service;

import java.util.List;

import com.aichat.Dto.CompleteTaskRequest;
import com.aichat.Dto.CreateTaskRequest;
import com.aichat.Dto.TaskVO;
import com.aichat.Dto.UpdateTaskRequest;

/**
 * 用户任务/计划服务（MindButler P1）。
 *
 * P1 范围：手动 CRUD + 完成/重复滚动 + 到期查询。
 * 口语化"AI 创建/确认"走 M3 的 IntentExtractor → PendingAction → 本服务的 create。
 * 归属安全：userId 只由实现层从 UserHolder 取；跨用户访问返回 null（Controller 转 404）。
 */
public interface TaskService {

    /** 查当前用户任务列表（status 默认 pending；排除软删；游标分页 beforeId，升序返回）。 */
    List<TaskVO> list(String status, Long beforeId, int limit);

    /** 查单条任务；非本人或已软删返回 null。 */
    TaskVO get(long id);

    /** 手动创建（不走 LLM）。recurrence 默认 once；weekly 必须有 recurrenceRule。 */
    TaskVO create(CreateTaskRequest request);

    /** 更新 pending 任务（乐观锁）。version 不匹配 → 409。 */
    TaskVO update(long id, UpdateTaskRequest request);

    /** 软删除（幂等）。返回 true 表示本次实际删了，false 表示已是删除态/不存在。 */
    boolean delete(long id);

    /** 完成：once → done；daily/weekly → 保持 pending + due_at 滚动到下一次。 */
    TaskVO complete(long id, CompleteTaskRequest request);

    /** 到期待办（status=pending、有 due_at 且已到期，按 due_at 升序；P1 只读不回写）。 */
    List<TaskVO> due(int limit);
}
