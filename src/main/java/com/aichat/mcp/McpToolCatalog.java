package com.aichat.mcp;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.model.function.FunctionCallback;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

import com.aichat.Exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * MCP 工具目录：按配置拉起服务端子进程，把它们的工具整理成模型可用的函数。
 *
 * <p>启动是惰性的——第一次真正用到时才拉进程。MCP 服务挂了只影响这一次工具调用，
 * 聊天主链路照常走（fail-open），不会因为一个外部子进程起不来就把首页打挂。
 */
@Component
public class McpToolCatalog implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(McpToolCatalog.class);

    private static final int MAX_TOOL_NAME_LENGTH = 64;

    private final McpProperties props;
    private final ObjectMapper mapper;

    private final List<McpClient> clients = new CopyOnWriteArrayList<>();
    private final Map<String, Binding> bindings = new LinkedHashMap<>();

    private volatile boolean started;
    private volatile List<FunctionCallback> callbacks = List.of();

    public McpToolCatalog(McpProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
    }

    /** 模型能看到的工具；模型不在白名单里就返回空列表，避免给不支持的模型挂工具。 */
    public List<FunctionCallback> callbacksFor(String model) {
        if (!props.isEnabled() || model == null) {
            return List.of();
        }
        List<String> allow = props.getToolModels();
        if (allow != null && !allow.isEmpty() && !allow.contains(model)) {
            return List.of();
        }
        ensureStarted();
        return callbacks;
    }

    public synchronized void ensureStarted() {
        if (started) {
            return;
        }
        started = true;
        if (!props.isEnabled()) {
            log.info("app.mcp.enabled=false，不启动 MCP 服务");
            return;
        }
        List<FunctionCallback> assembled = new ArrayList<>();
        for (Map.Entry<String, McpProperties.Server> entry : props.getServers().entrySet()) {
            McpProperties.Server cfg = entry.getValue();
            if (cfg == null || !cfg.isEnabled() || isBlank(cfg.getCommand())) {
                continue;
            }
            if (isBlank(cfg.getName())) {
                // 没写 name 就用配置里的 key，省得两处重复
                cfg.setName(entry.getKey());
            }
            try {
                registerServer(cfg, assembled);
            } catch (RuntimeException | IOException e) {
                log.warn("MCP 服务 {} 启动失败，其工具不可用: {}", cfg.getName(), e.getMessage());
            }
        }
        callbacks = List.copyOf(assembled);
        log.info("MCP 就绪：{} 个服务 / {} 个工具", clients.size(), bindings.size());
    }

    private void registerServer(McpProperties.Server cfg, List<FunctionCallback> assembled)
            throws IOException {
        McpClient client = openServer(cfg);
        clients.add(client);

        int exposed = 0;
        for (JsonNode tool : client.listTools()) {
            String rawName = tool.path("name").asText("");
            if (rawName.isBlank()) {
                continue;
            }
            if (cfg.getExpose() != null && !cfg.getExpose().isEmpty() && !cfg.getExpose().contains(rawName)) {
                continue;
            }
            String publicName = publicName(cfg.getName(), rawName);
            String description = tool.path("description").asText("");
            String schema = inputSchemaOf(tool);
            Binding binding = new Binding(publicName, cfg.getName(), rawName, description, client);
            bindings.put(publicName, binding);
            assembled.add(new McpFunctionCallback(publicName, description, schema, client, rawName));
            exposed++;
        }
        log.info("MCP 服务 {} 就绪，暴露 {} 个工具", cfg.getName(), exposed);
    }

    private String inputSchemaOf(JsonNode tool) {
        JsonNode schema = tool.path("inputSchema");
        if (schema.isObject()) {
            return schema.toString();
        }
        ObjectNode fallback = mapper.createObjectNode();
        fallback.put("type", "object");
        fallback.set("properties", mapper.createObjectNode());
        return fallback.toString();
    }

    private McpClient openServer(McpProperties.Server cfg) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(cfg.getCommand());
        if (cfg.getArgs() != null) {
            command.addAll(cfg.getArgs());
        }
        // 相对路径统一成绝对路径：IDE 启动和命令行启动的 cwd 不一定一样
        for (int i = 1; i < command.size(); i++) {
            String arg = command.get(i);
            if (arg != null && !arg.startsWith("-")) {
                File file = new File(arg);
                if (file.isFile()) {
                    command.set(i, file.getAbsolutePath());
                }
            }
        }

        McpClient client = McpClient.start(cfg.getName(), new ProcessBuilder(command), mapper,
                props.getTimeoutMs());
        try {
            client.initialize();
            return client;
        } catch (RuntimeException e) {
            client.close();
            throw e;
        }
    }

    /** 直接调用某个工具（REST 调试入口用，不经过模型）。 */
    public String invoke(String publicName, String argumentsJson) {
        ensureStarted();
        Binding binding = bindings.get(publicName);
        if (binding == null) {
            throw new BusinessException(404, "未知工具: " + publicName);
        }
        McpToolResult result;
        try {
            result = binding.client().callTool(binding.rawName(), argumentsJson);
        } catch (McpException e) {
            throw new BusinessException(503, "工具调用失败: " + e.getMessage());
        }
        if (result.error()) {
            throw new BusinessException(503, "工具执行失败: " + result.text());
        }
        return result.text();
    }

    public List<McpToolInfo> toolInfos() {
        ensureStarted();
        List<McpToolInfo> infos = new ArrayList<>(bindings.size());
        for (Binding binding : bindings.values()) {
            infos.add(new McpToolInfo(binding.publicName(), binding.server(), binding.rawName(),
                    binding.description()));
        }
        return infos;
    }

    private static String publicName(String server, String tool) {
        String cleaned = ("mcp_" + server + "_" + tool).replaceAll("[^A-Za-z0-9_-]", "_");
        return cleaned.length() <= MAX_TOOL_NAME_LENGTH ? cleaned : cleaned.substring(0, MAX_TOOL_NAME_LENGTH);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    @Override
    public void destroy() {
        clients.forEach(McpClient::close);
        clients.clear();
    }

    private record Binding(String publicName, String server, String rawName, String description,
            McpClient client) {
    }

    /** 对外暴露的工具信息。 */
    public record McpToolInfo(String name, String server, String tool, String description) {
    }
}
