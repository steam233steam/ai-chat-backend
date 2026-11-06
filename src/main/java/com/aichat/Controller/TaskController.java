package com.aichat.Controller;

import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.aichat.Config.MindButlerProperties;
import com.aichat.Dto.CompleteTaskRequest;
import com.aichat.Dto.CreateTaskRequest;
import com.aichat.Dto.Result;
import com.aichat.Dto.TaskVO;
import com.aichat.Dto.UpdateTaskRequest;
import com.aichat.Exception.BusinessException;
import com.aichat.Service.TaskService;

import jakarta.validation.Valid;

/**
 * 用户任务/计划接口（MindButler P1）。
 * 按当前登录用户隔离；跨用户访问统一 404（避免枚举）。
 * 手动 CRUD + 完成 + 到期查询；口语化"AI 创建/确认"走 M3 提取器 → pending → 本服务 create。
 */
@RestController
@RequestMapping("/user/tasks")
@Tag(name = "用户任务", description = "口语化任务/计划的手动管理 + 完成 + 到期查询")
public class TaskController {

    private final TaskService taskService;
    private final MindButlerProperties props;

    public TaskController(TaskService taskService, MindButlerProperties props) {
        this.taskService = taskService;
        this.props = props;
    }

    @Operation(summary = "查询到期待办",
            description = "status=pending、有截止时间且已到期，按 due_at 升序；P1 只读不回写 last_notified_at，前端按 (id,dueAt) 去重")
    @GetMapping("/due")
    public Result<List<TaskVO>> due(@RequestParam(defaultValue = "50") int limit) {
        int cap = Math.max(1, Math.min(limit, props.getTaskDueLimit()));
        return Result.success(taskService.due(cap));
    }

    @Operation(summary = "任务列表",
            description = "默认 status=pending，排除软删；游标分页 beforeId（id < beforeId，升序返回）；limit 1-100")
    @GetMapping
    public Result<List<TaskVO>> list(
            @RequestParam(defaultValue = "pending") String status,
            @RequestParam(required = false) Long beforeId,
            @RequestParam(defaultValue = "50") int limit) {
        return Result.success(taskService.list(status, beforeId, limit));
    }

    @Operation(summary = "查单条任务",
            description = "含 overdue 计算字段；非本人或已软删返回 404")
    @GetMapping("/{id}")
    public Result<TaskVO> get(@PathVariable long id) {
        TaskVO vo = taskService.get(id);
        if (vo == null) {
            throw new BusinessException(404, "任务不存在或已删除");
        }
        return Result.success(vo);
    }

    @Operation(summary = "手动创建任务（兜底）",
            description = "不走 LLM；recurrence 默认 once；weekly 必须带 recurrenceRule；recurring 必须有 dueAt")
    @PostMapping
    public Result<TaskVO> create(@Valid @RequestBody CreateTaskRequest request) {
        return Result.success(taskService.create(request));
    }

    @Operation(summary = "更新任务（乐观锁）",
            description = "只能改 pending 任务；status 只允许 pending/cancelled；version 不匹配返回 409")
    @PutMapping("/{id}")
    public Result<TaskVO> update(@PathVariable long id, @Valid @RequestBody UpdateTaskRequest request) {
        TaskVO vo = taskService.update(id, request);
        if (vo == null) {
            throw new BusinessException(404, "任务不存在或已删除");
        }
        return Result.success(vo);
    }

    @Operation(summary = "完成任务",
            description = "once → done；daily/weekly → 保持 pending 且 due_at 滚动到下一次；version 不匹配 409")
    @PostMapping("/{id}/complete")
    public Result<TaskVO> complete(@PathVariable long id, @Valid @RequestBody CompleteTaskRequest request) {
        TaskVO vo = taskService.complete(id, request);
        if (vo == null) {
            throw new BusinessException(404, "任务不存在或已删除");
        }
        return Result.success(vo);
    }

    @Operation(summary = "删除任务（软删）",
            description = "幂等；已是删除态仍返回 200；不存在/非本人返回 404")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable long id) {
        boolean ok = taskService.delete(id);
        if (!ok) {
            throw new BusinessException(404, "任务不存在或已删除");
        }
        return Result.success(null);
    }
}
