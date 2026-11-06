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

import com.aichat.Dto.CreateMemoryRequest;
import com.aichat.Dto.MemoryVO;
import com.aichat.Dto.Result;
import com.aichat.Dto.UpdateMemoryRequest;
import com.aichat.Exception.BusinessException;
import com.aichat.Service.MemoryService;

import jakarta.validation.Valid;

/**
 * 用户长期记忆接口（MindButler P1）。
 * 所有接口按当前登录用户隔离，userId 由拦截器写入 UserHolder，不接受请求体传 userId。
 * 跨用户访问统一 404（避免枚举）。
 */
@RestController
@RequestMapping("/user/memories")
@Tag(name = "用户记忆", description = "长期记忆（结构化画像）的手动管理 + 导出")
public class MemoryController {

    private final MemoryService memoryService;

    public MemoryController(MemoryService memoryService) {
        this.memoryService = memoryService;
    }

    @Operation(summary = "查询生效记忆",
            description = "返回当前用户 active 且未过期的记忆，按类型/更新时间倒序；limit 控制总数 1-100；"
                    + "P1.3 支持 sessionId 参数按会话隔离过滤（不传则返回全部会话+全局记忆）")
    @GetMapping
    public Result<List<MemoryVO>> list(
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(required = false) String sessionId) {
        if (limit < 1 || limit > 100) {
            throw new BusinessException("limit 必须在 1-100 之间");
        }
        return Result.success(memoryService.list(limit, sessionId));
    }

    @Operation(summary = "导出全部记忆",
            description = "导出当前用户全部未删除记忆（含 superseded），用于隐私查看/数据迁移")
    @GetMapping("/export")
    public Result<List<MemoryVO>> export() {
        return Result.success(memoryService.export());
    }

    @Operation(summary = "查单条记忆",
            description = "含 status 与 expired 计算字段；非本人或已删除返回 404")
    @GetMapping("/{id}")
    public Result<MemoryVO> get(@PathVariable long id) {
        MemoryVO vo = memoryService.get(id);
        if (vo == null) {
            throw new BusinessException(404, "记忆不存在或已删除");
        }
        return Result.success(vo);
    }

    @Operation(summary = "手动创建记忆",
            description = "source=manual、confidence=1.00；stable 不可设过期；内容命中敏感词直接拒绝")
    @PostMapping
    public Result<MemoryVO> create(@Valid @RequestBody CreateMemoryRequest request) {
        return Result.success(memoryService.create(request));
    }

    @Operation(summary = "更新记忆（乐观锁）",
            description = "只能改 active 且未过期的记忆；version 不匹配返回 409")
    @PutMapping("/{id}")
    public Result<MemoryVO> update(@PathVariable long id, @Valid @RequestBody UpdateMemoryRequest request) {
        MemoryVO vo = memoryService.update(id, request);
        if (vo == null) {
            throw new BusinessException(404, "记忆不存在或已删除");
        }
        return Result.success(vo);
    }

    @Operation(summary = "删除记忆（软删）",
            description = "幂等；已是删除态仍返回 200；不存在/非本人返回 404")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable long id) {
        boolean ok = memoryService.delete(id);
        if (!ok) {
            throw new BusinessException(404, "记忆不存在或已删除");
        }
        return Result.success(null);
    }
}
