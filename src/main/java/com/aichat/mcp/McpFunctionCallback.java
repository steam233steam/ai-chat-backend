package com.aichat.mcp;

import org.springframework.ai.model.function.FunctionCallback;

/**
 * 把 MCP 工具包装成 Spring AI 的 {@link FunctionCallback}，让模型能直接调。
 *
 * <p>模型只看到拼装后的公共名、描述和 inputSchema；真正调用时换回服务端原始工具名。
 * 工具执行失败不抛异常——返回一段错误文案给模型，让它自己决定要不要换个说法，
 * 否则一次网络抖动就会把整轮对话变成 500。
 */
class McpFunctionCallback implements FunctionCallback {

    private final String publicName;
    private final String description;
    private final String inputSchema;
    private final McpClient client;
    private final String rawToolName;

    McpFunctionCallback(String publicName, String description, String inputSchema,
            McpClient client, String rawToolName) {
        this.publicName = publicName;
        this.description = description;
        this.inputSchema = inputSchema;
        this.client = client;
        this.rawToolName = rawToolName;
    }

    @Override
    public String getName() {
        return publicName;
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public String getInputTypeSchema() {
        return inputSchema;
    }

    @Override
    public String call(String toolInput) {
        try {
            McpToolResult result = client.callTool(rawToolName, toolInput);
            return result.error() ? "工具执行失败：" + result.text() : result.text();
        } catch (RuntimeException e) {
            return "工具调用失败：" + e.getMessage();
        }
    }
}
