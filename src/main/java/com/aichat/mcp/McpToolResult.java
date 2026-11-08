package com.aichat.mcp;

/**
 * 一次 tools/call 的结果。
 *
 * @param text  所有 text 内容块拼起来的结果
 * @param error 服务端标记的 isError；业务失败也走这里而不是抛异常
 */
public record McpToolResult(String text, boolean error) {
}
