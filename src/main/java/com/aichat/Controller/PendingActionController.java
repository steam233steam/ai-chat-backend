package com.aichat.Controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.aichat.Dto.ConfirmResult;
import com.aichat.Dto.PendingActionVO;
import com.aichat.Dto.Result;
import com.aichat.Service.PendingActionService;

/**
 * 待确认动作接口（MindButler P1）。
 * M3 口语路径：聊天里命中触发词 → 提取器写 pending → 前端/聊天用"确认/取消"；
 * 这里的 REST 是"确认卡片"入口（用户点确认/取消）+ 当前 pending 查询。
 */
@RestController
@RequestMapping("/user/pending-actions")
@Tag(name = "待确认动作", description = "AI 提案的记忆/任务，用户确认后才生效（v3 状态机）")
public class PendingActionController {

    @Autowired
    private PendingActionService pendingActionService;

    @Operation(summary = "查当前会话的待确认动作",
            description = "没有/已过期返回 data=null；前端据此渲染确认卡片")
    @GetMapping("/current")
    public Result<PendingActionVO> current(@RequestParam String sessionId) {
        return Result.success(pendingActionService.current(sessionId));
    }

    @Operation(summary = "按 id 查单条待确认动作",
            description = "前端拿到 /chat/ask 文案里的 pendingId 后调本接口拉弹窗详情；"
                    + "非本人/不存在/已过期返回 data=null（懒过期语义同 current）")
    @GetMapping("/{id}")
    public Result<PendingActionVO> getById(@PathVariable long id) {
        return Result.success(pendingActionService.getById(id));
    }

    @Operation(summary = "确认待确认动作",
            description = "原子确认；重复确认返回 alreadyConfirmed=true（不重复建）；已拒绝/过期 409")
    @PostMapping("/{id}/confirm")
    public Result<ConfirmResult> confirm(@PathVariable long id) {
        return Result.success(pendingActionService.confirm(id));
    }

    @Operation(summary = "拒绝待确认动作",
            description = "幂等；已拒绝静默成功，已确认/过期 409")
    @PostMapping("/{id}/reject")
    public Result<Void> reject(@PathVariable long id) {
        pendingActionService.reject(id);
        return Result.success(null);
    }
}
