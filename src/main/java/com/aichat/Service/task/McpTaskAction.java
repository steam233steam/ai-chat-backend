package com.aichat.Service.task;

import java.time.LocalDateTime;

import org.slf4j.LoggerFactory;
import org.slf4j.Logger;
import org.springframework.stereotype.Component;

import com.aichat.Entity.UserTask;
import com.aichat.mcp.McpToolCatalog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 调用 MCP 工具的动作：到点让 AI 去查一次外部信息（天气、搜索、时间、抓网页），把结果写进执行记录。
 *
 * action_config 形如 {"tool":"mcp_info_weather","args":{"city":"北京"}}，
 * 其中 tool 用 GET /mcp/tools 返回的公共工具名（mcp_&lt;server&gt;_&lt;tool&gt;）。
 * 只允许调用信息查询类工具——文件读写这类有副作用的工具不适合放在无人值守的定时任务里。
 */
@Component
public class McpTaskAction implements TaskAction {

    private static final org.slf4j.Logger log = LoggerFactory.getLogger(McpTaskAction.class);

    private final McpToolCatalog catalog;
    private final ObjectMapper mapper;

    public McpTaskAction(McpToolCatalog catalog, ObjectMapper mapper) {
        this.catalog = catalog;
        this.mapper = mapper;
    }

    @Override
    public String name() {
        return "mcp";
    }

    @Override
    public String run(UserTask task, LocalDateTime now) throws Exception {
        String config = task.getActionConfig();
        if (config == null || config.isBlank()) {
            throw new IllegalStateException("MCP 动作缺少 action_config");
        }
        JsonNode node = mapper.readTree(config);
        String tool = node.path("tool").asText("");
        if (tool.isBlank()) {
            throw new IllegalStateException("MCP 动作缺少 tool 字段");
        }
        if (!tool.startsWith("mcp_")) {
            throw new IllegalStateException("MCP 工具名应以 mcp_ 开头：" + tool);
        }
        String args = node.has("args") && !node.get("args").isNull()
                ? mapper.writeValueAsString(node.get("args"))
                : "{}";
        catalog.ensureStarted();
        String result = catalog.invoke(tool, args);
        String flat = result == null ? "" : result.replaceAll("\\s+", " ").trim();
        if (flat.length() > 300) {
            flat = flat.substring(0, 300) + "…";
        }
        log.info("任务 {} 调用 MCP 工具 {}，返回 {} 字", task.getId(), tool, flat.length());
        return task.getTitle() + "：" + flat;
    }
}
