package com.aichat.mcp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真起一个 MCP 服务端进程（仓库里的 mcp-servers/info-server.mjs），验证握手、列工具、调工具三步。
 *
 * <p>需要本机有 node；没有就跳过，不让 CI 因为缺运行时变红。
 */
class McpClientTest {

    private static final Path SERVER_SCRIPT = Paths.get("mcp-servers", "info-server.mjs");

    @Test
    void handshakeListsToolsAndCallsNow() throws Exception {
        Assumptions.assumeTrue(Files.exists(SERVER_SCRIPT), "缺少 mcp-servers/info-server.mjs");
        Assumptions.assumeTrue(nodeAvailable(), "本机没有 node，跳过 MCP 端到端测试");

        McpClient client = McpClient.start("info",
                new ProcessBuilder("node", SERVER_SCRIPT.toAbsolutePath().toString()),
                new ObjectMapper(), 15_000);
        try {
            client.initialize();

            List<JsonNode> tools = client.listTools();
            assertTrue(tools.stream().anyMatch(t -> "now".equals(t.path("name").asText())),
                    "tools/list 里应该有 now");

            McpToolResult result = client.callTool("now", "{\"tz\":\"Asia/Shanghai\"}");
            assertFalse(result.error(), "调用 now 不应返回 isError");
            assertTrue(result.text().contains("Asia/Shanghai"), "结果里应带上请求的时区");
        } finally {
            client.close();
        }
    }

    @Test
    void unknownToolIsReportedAsToolError() throws Exception {
        Assumptions.assumeTrue(Files.exists(SERVER_SCRIPT), "缺少 mcp-servers/info-server.mjs");
        Assumptions.assumeTrue(nodeAvailable(), "本机没有 node，跳过 MCP 端到端测试");

        McpClient client = McpClient.start("info",
                new ProcessBuilder("node", SERVER_SCRIPT.toAbsolutePath().toString()),
                new ObjectMapper(), 15_000);
        try {
            client.initialize();
            McpToolResult result = client.callTool("no_such_tool", "{}");
            assertTrue(result.error() || result.text().contains("未知工具"),
                    "未知工具应报错而不是静默成功");
        } catch (McpException e) {
            assertEquals(true, e.getMessage().contains("未知工具") || e.getMessage().contains("-32602"));
        } finally {
            client.close();
        }
    }

    private static boolean nodeAvailable() {
        try {
            Process process = new ProcessBuilder("node", "--version").redirectErrorStream(true).start();
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
