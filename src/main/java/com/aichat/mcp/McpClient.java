package com.aichat.mcp;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 最小可用的 MCP 客户端：stdio 传输 + 换行分隔的 JSON-RPC 2.0。
 *
 * <p>只实现本项目需要的三段：initialize / tools/list / tools/call。
 * 服务端反向发起的请求（roots/list、sampling/createMessage）一律按 JSON-RPC 规范回 -32601，
 * 不做采样与根目录协商——聊天业务用不到，也没必要把用户目录暴露给第三方服务端。
 *
 * <p>不是线程安全的“会话”：一个客户端对应一个服务端进程，多个请求靠自增 id 复用同一条管道。
 */
public class McpClient implements AutoCloseable {

    /** MCP 用日期做协议版本号，2024-11-05 是目前被广泛实现的版本。 */
    private static final String PROTOCOL_VERSION = "2024-11-05";

    private static final int MAX_STDERR_LINES = 20;

    private final String serverName;
    private final Process process;
    private final BufferedWriter writer;
    private final ObjectMapper mapper;
    private final int timeoutMs;

    private final Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final AtomicLong nextId = new AtomicLong(1);
    private final Deque<String> stderrTail = new ArrayDeque<>();

    private final Thread readerThread;
    private final Thread stderrThread;

    private volatile boolean closed;

    private McpClient(String serverName, Process process, ObjectMapper mapper, int timeoutMs) {
        this.serverName = serverName;
        this.process = process;
        this.mapper = mapper;
        this.timeoutMs = timeoutMs;
        this.writer = new BufferedWriter(
                new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        this.readerThread = new Thread(this::readLoop, "mcp-" + serverName + "-reader");
        this.readerThread.setDaemon(true);
        this.stderrThread = new Thread(this::drainStderr, "mcp-" + serverName + "-stderr");
        this.stderrThread.setDaemon(true);
        this.readerThread.start();
        this.stderrThread.start();
    }

    public static McpClient start(String serverName, ProcessBuilder processBuilder,
            ObjectMapper mapper, int timeoutMs) throws IOException {
        Process process = processBuilder.redirectErrorStream(false).start();
        return new McpClient(serverName, process, mapper, timeoutMs);
    }

    /** 握手：initialize → notifications/initialized。 */
    public void initialize() {
        ObjectNode params = mapper.createObjectNode();
        params.put("protocolVersion", PROTOCOL_VERSION);
        params.set("capabilities", mapper.createObjectNode());
        ObjectNode clientInfo = mapper.createObjectNode();
        clientInfo.put("name", "aichat");
        clientInfo.put("version", "1.0");
        params.set("clientInfo", clientInfo);
        request("initialize", params);

        ObjectNode notification = mapper.createObjectNode();
        notification.put("jsonrpc", "2.0");
        notification.put("method", "notifications/initialized");
        try {
            send(notification);
        } catch (IOException e) {
            throw new McpException("向 MCP 服务 " + serverName + " 发送 initialized 通知失败: " + e.getMessage());
        }
    }

    public List<JsonNode> listTools() {
        JsonNode result = request("tools/list", null);
        List<JsonNode> tools = new ArrayList<>();
        JsonNode array = result.path("tools");
        if (array.isArray()) {
            array.forEach(tools::add);
        }
        return tools;
    }

    /** 调工具。argumentsJson 是模型给出的参数 JSON，允许为空串。 */
    public McpToolResult callTool(String toolName, String argumentsJson) {
        ObjectNode params = mapper.createObjectNode();
        params.put("name", toolName);
        params.set("arguments", parseArguments(argumentsJson));

        JsonNode result = request("tools/call", params);
        StringBuilder text = new StringBuilder();
        for (JsonNode block : result.path("content")) {
            if ("text".equals(block.path("type").asText())) {
                if (text.length() > 0) {
                    text.append('\n');
                }
                text.append(block.path("text").asText());
            }
        }
        return new McpToolResult(text.toString(), result.path("isError").asBoolean(false));
    }

    private ObjectNode parseArguments(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return mapper.createObjectNode();
        }
        try {
            JsonNode parsed = mapper.readTree(argumentsJson);
            return parsed instanceof ObjectNode objectNode ? objectNode : mapper.createObjectNode();
        } catch (IOException e) {
            throw new McpException("工具参数不是合法 JSON: " + e.getMessage());
        }
    }

    private JsonNode request(String method, ObjectNode params) {
        if (closed) {
            throw new McpException("MCP 服务 " + serverName + " 已关闭");
        }
        long id = nextId.getAndIncrement();
        ObjectNode message = mapper.createObjectNode();
        message.put("jsonrpc", "2.0");
        message.put("id", id);
        message.put("method", method);
        if (params != null) {
            message.set("params", params);
        }

        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(id, future);
        try {
            try {
                send(message);
            } catch (IOException e) {
                pending.remove(id);
                throw new McpException("向 MCP 服务 " + serverName + " 写入失败: " + e.getMessage());
            }
            JsonNode response = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            JsonNode error = response.get("error");
            if (error != null && !error.isNull()) {
                throw new McpException("MCP 服务 " + serverName + " 返回错误(" + method + "): "
                        + error.path("message").asText());
            }
            return response.path("result");
        } catch (TimeoutException e) {
            pending.remove(id);
            throw new McpException("MCP 服务 " + serverName + " 超时(" + timeoutMs + "ms): " + method + stderrHint());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpException("等待 MCP 服务 " + serverName + " 响应时被中断");
        } catch (ExecutionException e) {
            throw new McpException("MCP 服务 " + serverName + " 通信失败: " + e.getCause());
        } finally {
            pending.remove(id);
        }
    }

    private synchronized void send(JsonNode message) throws IOException {
        if (closed) {
            throw new IOException("client closed");
        }
        writer.write(mapper.writeValueAsString(message));
        writer.write('\n');
        writer.flush();
    }

    private void readLoop() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode message;
                try {
                    message = mapper.readTree(line);
                } catch (IOException e) {
                    continue;
                }
                dispatch(message);
            }
        } catch (IOException ignored) {
            // 进程退出时流会先断，这里只负责把等待中的请求叫醒
        } finally {
            failAllPending("MCP 服务 " + serverName + " 已退出");
        }
    }

    private void dispatch(JsonNode message) {
        JsonNode idNode = message.get("id");
        if (message.hasNonNull("method")) {
            if (idNode != null && !idNode.isNull()) {
                replyUnsupported(idNode);
            }
            return;
        }
        if (idNode == null || idNode.isNull()) {
            return;
        }
        CompletableFuture<JsonNode> future = pending.remove(idNode.asLong());
        if (future != null) {
            future.complete(message);
        }
    }

    private void replyUnsupported(JsonNode idNode) {
        ObjectNode reply = mapper.createObjectNode();
        reply.put("jsonrpc", "2.0");
        reply.set("id", idNode);
        ObjectNode error = mapper.createObjectNode();
        error.put("code", -32601);
        error.put("message", "aichat 客户端不支持该请求");
        reply.set("error", error);
        try {
            send(reply);
        } catch (IOException ignored) {
            // 服务端已经断开，回不回都无所谓
        }
    }

    private void drainStderr() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                synchronized (stderrTail) {
                    if (stderrTail.size() >= MAX_STDERR_LINES) {
                        stderrTail.removeFirst();
                    }
                    stderrTail.addLast(line);
                }
            }
        } catch (IOException ignored) {
            // 进程结束
        }
    }

    private String stderrHint() {
        synchronized (stderrTail) {
            return stderrTail.isEmpty() ? "" : "，服务端 stderr: " + String.join(" | ", stderrTail);
        }
    }

    private void failAllPending(String reason) {
        pending.forEach((id, future) -> future.completeExceptionally(new McpException(reason)));
        pending.clear();
    }

    public boolean isAlive() {
        return !closed && process.isAlive();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        failAllPending("MCP 服务 " + serverName + " 已关闭");
        try {
            writer.close();
        } catch (IOException ignored) {
            // 管道已断
        }
        process.destroy();
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }
}
