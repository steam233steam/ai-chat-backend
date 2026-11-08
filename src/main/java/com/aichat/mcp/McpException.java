package com.aichat.mcp;

/** MCP 通信或工具执行失败。对外统一转成 503，避免把子进程细节暴露给前端。 */
public class McpException extends RuntimeException {

    public McpException(String message) {
        super(message);
    }
}
