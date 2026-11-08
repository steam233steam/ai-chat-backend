package com.aichat.Controller;

import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aichat.Dto.Result;
import com.aichat.mcp.McpToolCatalog;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * MCP 工具的查看与调试入口。
 *
 * <p>模型侧走的是 Spring AI 的函数调用；这里单独开 REST 是为了三件事：
 * 前端展示「当前能查什么」、出问题时不用过模型就能复现、以及给不支持工具调用的本地模型留后路。
 */
@RestController
@RequestMapping("/mcp")
@Tag(name = "MCP 工具", description = "查看可用的信息查询工具，并可直接调用（不经过模型）")
public class McpController {

    private final McpToolCatalog catalog;

    public McpController(McpToolCatalog catalog) {
        this.catalog = catalog;
    }

    @Operation(summary = "列出可用工具", description = "返回所有已连接 MCP 服务暴露的工具名、所属服务与描述")
    @GetMapping("/tools")
    public Result<List<McpToolCatalog.McpToolInfo>> tools() {
        return Result.success(catalog.toolInfos());
    }

    @Operation(summary = "直接调用工具", description = "请求体是该工具的参数 JSON，如 {\"city\":\"北京\"}；返回工具的文本结果")
    @PostMapping("/tools/{name}")
    public Result<String> invoke(@PathVariable String name,
            @RequestBody(required = false) JsonNode arguments) {
        String args = arguments == null ? "{}" : arguments.toString();
        return Result.success(catalog.invoke(name, args));
    }
}
