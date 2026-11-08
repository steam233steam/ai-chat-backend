package com.aichat.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.model.function.FunctionCallback;

/**
 * 把 MCP 工具包装成 Spring AI 的 {@link FunctionCallback}，让模型能直接调。
 *
 * <p>模型只看到拼装后的公共名、描述和 inputSchema；真正调用时换回服务端原始工具名。
 * 工具执行失败不抛异常——返回一段错误文案给模型，让它自己决定要不要换个说法，
 * 否则一次网络抖动就会把整轮对话变成 500。
 */
class McpFunctionCallback implements FunctionCallback {

    private static final Logger log = LoggerFactory.getLogger(McpFunctionCallback.class);

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
        // 打日志是为了排「模型到底调没调工具」——调用方看不到模型内部决策，只能从这行确认
        log.info("模型调用 MCP 工具 {}，参数 {}", publicName, abbreviate(toolInput));
        long start = System.currentTimeMillis();
        try {
            McpToolResult result = client.callTool(rawToolName, toolInput);
            log.info("MCP 工具 {} 返回 {} 字符、{}ms", publicName, result.text().length(),
                    System.currentTimeMillis() - start);
            return result.error() ? "工具执行失败：" + result.text() : result.text();
        } catch (RuntimeException e) {
            log.warn("MCP 工具 {} 调用失败：{}", publicName, e.getMessage());
            return "工具调用失败：" + e.getMessage();
        }
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "null";
        }
        return text.length() <= 300 ? text : text.substring(0, 300) + "…";
    }
}
