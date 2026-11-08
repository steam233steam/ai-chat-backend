package com.aichat.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * MCP 接入配置。
 *
 * <pre>
 * app.mcp.enabled=true
 * app.mcp.tool-models=deepseek-chat          # 哪些模型能用这些工具；留空=所有模型
 * app.mcp.servers.info.command=node
 * app.mcp.servers.info.args[0]=mcp-servers/info-server.mjs
 * app.mcp.servers.info.enabled=true
 * </pre>
 *
 * <p>servers 用 Map（key 就是服务名）而不是 List：多个配置文件里的条目能合并，
 * 不会像 <code>servers[1].*</code> 那样因为索引不连续而整段绑定失败。
 */
@ConfigurationProperties(prefix = "app.mcp")
public class McpProperties {

    /** 总开关。关闭后不启动任何子进程，模型侧也不会挂工具。 */
    private boolean enabled = true;

    /** 单次 tools/call 的超时时间（毫秒）。 */
    private int timeoutMs = 15_000;

    /**
     * 允许使用 MCP 工具的模型白名单。本地 7B 模型（qwen2.5-coder）的 Ollama 模板
     * 只会把工具调用打印成文本 JSON，实际上走不通，所以默认只给 deepseek-chat 挂工具。
     * 留空表示所有模型都挂。
     */
    private List<String> toolModels = new ArrayList<>(List.of("deepseek-chat"));

    /** key = 服务名，可跨配置文件追加。 */
    private Map<String, Server> servers = new LinkedHashMap<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(int timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public List<String> getToolModels() {
        return toolModels;
    }

    public void setToolModels(List<String> toolModels) {
        this.toolModels = toolModels;
    }

    public Map<String, Server> getServers() {
        return servers;
    }

    public void setServers(Map<String, Server> servers) {
        this.servers = servers;
    }

    /** 一个 MCP 服务端进程的启动参数。 */
    public static class Server {

        private String name;

        private String command;

        private List<String> args = new ArrayList<>();

        /** 只暴露这些工具（服务端原始名字）；为空表示全暴露。 */
        private List<String> expose = new ArrayList<>();

        private boolean enabled = true;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getCommand() {
            return command;
        }

        public void setCommand(String command) {
            this.command = command;
        }

        public List<String> getArgs() {
            return args;
        }

        public void setArgs(List<String> args) {
            this.args = args;
        }

        public List<String> getExpose() {
            return expose;
        }

        public void setExpose(List<String> expose) {
            this.expose = expose;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
