package com.aichat.Service.task;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.aichat.Entity.UserTask;
import com.aichat.mcp.McpToolCatalog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 动作链：到点后按顺序做几件事，后一步可以引用前一步的结果。
 *
 * 只支持两种步骤：{@code mcp}（调一个 MCP 工具取数据）和 {@code push}（把内容推到手机）。
 * 刻意不做条件分支、循环、并行——那是工作流引擎的活，这里只解决「查完再发」这一个高频场景。
 *
 * 配置示例：
 * <pre>
 * {"steps":[
 *   {"id":"s1","type":"mcp","tool":"mcp_info_search","args":{"q":"LOL S赛 赛果"}},
 *   {"id":"s2","type":"push","channel":"serverchan","sendkey":"SCT...","content":"{{s1}}"}
 * ]}
 * </pre>
 */
@Component
public class ChainTaskAction implements TaskAction {

    private static final Logger log = LoggerFactory.getLogger(ChainTaskAction.class);

    /** 引用上一步结果时最多带多少字，避免一条推送被撑爆。 */
    static final int REFERENCE_MAX_CHARS = 1000;

    /** 一条链最多几步，防止配置写飞。 */
    static final int MAX_STEPS = 5;

    private final McpToolCatalog catalog;
    private final PushTaskAction pushTaskAction;
    private final ObjectMapper mapper;

    public ChainTaskAction(McpToolCatalog catalog, PushTaskAction pushTaskAction, ObjectMapper mapper) {
        this.catalog = catalog;
        this.pushTaskAction = pushTaskAction;
        this.mapper = mapper;
    }

    @Override
    public String name() {
        return "chain";
    }

    @Override
    public String run(UserTask task, LocalDateTime now) throws Exception {
        JsonNode config = mapper.readTree(task.getActionConfig() == null ? "{}" : task.getActionConfig());
        JsonNode steps = config.path("steps");
        if (!steps.isArray() || steps.isEmpty()) {
            throw new IllegalStateException("动作链没有配置步骤");
        }
        if (steps.size() > MAX_STEPS) {
            throw new IllegalStateException("动作链最多 " + MAX_STEPS + " 步");
        }

        // 每一步的输出按 id 存起来，供后面的步骤用 {{id}} 引用
        Map<String, String> outputs = new LinkedHashMap<>();
        List<String> logLines = new ArrayList<>();

        for (int i = 0; i < steps.size(); i++) {
            JsonNode step = steps.get(i);
            String id = text(step, "id", "s" + (i + 1));
            String type = text(step, "type", "").toLowerCase();
            try {
                String output = switch (type) {
                    case "mcp" -> runMcpStep(step, outputs, task);
                    case "push" -> runPushStep(step, outputs, task);
                    default -> throw new IllegalStateException("不支持的步骤类型：" + type);
                };
                outputs.put(id, output);
                logLines.add("%s %s %s → %s".formatted(id, type, summarize(step, type), abbreviate(output, 60)));
            } catch (Exception e) {
                // 有些 JDK 的 ConnectException 没有 message，直接拼 e.getMessage() 会出现「失败：null」
                String reason = describe(e);
                logLines.add("%s %s 失败：%s".formatted(id, type, reason));
                log.warn("动作链第 {} 步失败 taskId={} step={} type={}: {}", i + 1, task.getId(), id, type, reason);
                throw new IllegalStateException(
                        "第 " + (i + 1) + " 步（" + id + "）失败：" + reason
                                + (logLines.size() > 1 ? "；已执行：" + String.join(" | ", logLines) : ""), e);
            }
        }
        return String.join("\n", logLines);
    }

    private String runMcpStep(JsonNode step, Map<String, String> outputs, UserTask task) {
        String tool = text(step, "tool", "");
        if (!tool.startsWith("mcp_")) {
            throw new IllegalStateException("MCP 步骤的 tool 应以 mcp_ 开头");
        }
        String args = interpolate(stringify(step.path("args")), outputs);
        String result = catalog.invoke(tool, args);
        if (result == null || result.isBlank()) {
            return "（无返回内容）";
        }
        return result;
    }

    private String runPushStep(JsonNode step, Map<String, String> outputs, UserTask task) throws Exception {
        String content = text(step, "content", "");
        if (content.isEmpty()) {
            content = text(step, "message", "");
        }
        if (content.isEmpty()) {
            content = "{{s1}}";
        }
        String title = text(step, "title", task.getTitle() == null ? "任务提醒" : task.getTitle());
        JsonNode config = step.deepCopy();
        return pushTaskAction.send(config, title, abbreviate(interpolate(content, outputs), 1500), task.getId());
    }

    /** 把 {{stepId}} 换成前一步的输出，单个引用最多 1000 字。 */
    static String interpolate(String template, Map<String, String> outputs) {
        if (template == null || template.isEmpty() || outputs.isEmpty()) {
            return template == null ? "" : template;
        }
        String result = template;
        for (Map.Entry<String, String> entry : outputs.entrySet()) {
            String placeholder = "{{" + entry.getKey() + "}}";
            if (result.contains(placeholder)) {
                result = result.replace(placeholder, abbreviate(entry.getValue(), REFERENCE_MAX_CHARS));
            }
        }
        return result;
    }

    private static String stringify(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "{}";
        }
        return node.toString();
    }

    private static String summarize(JsonNode step, String type) {
        return "mcp".equals(type) ? text(step, "tool", "") : text(step, "channel", "");
    }

    private static String text(JsonNode node, String field, String fallback) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? fallback : value.asText(fallback);
    }

    /** 异常文案兜底：message 为空时用异常类名，至少能看出是连接失败还是超时。 */
    static String describe(Throwable e) {
        String message = e.getMessage();
        if (message != null && !message.isBlank()) {
            return message;
        }
        Throwable cause = e.getCause();
        if (cause != null && cause.getMessage() != null && !cause.getMessage().isBlank()) {
            return cause.getMessage();
        }
        return e.getClass().getSimpleName();
    }

    static String abbreviate(String text, int max) {
        if (text == null) {
            return "";
        }
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= max ? flat : flat.substring(0, max) + "…";
    }
}
