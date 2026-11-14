package com.aichat.Service.task;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.aichat.Config.ModelRouter;
import com.aichat.Entity.UserTask;
import com.aichat.mcp.McpToolCatalog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 「AI 查询」动作：到点先让模型把任务拆成几条搜索词，把搜到的正文喂回去再总结。
 *
 * <p>固定单次搜索做不到「和聊天里带联网搜索的 AI 一样」——模型只调一次工具，
 * 词没起好就拿到一堆官网首页。这里补的正是那一步：换词、多次取资料、再作答。
 */
@Component
public class AgentTaskAction implements TaskAction {

    private static final Logger log = LoggerFactory.getLogger(AgentTaskAction.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy年M月d日");
    private static final int MAX_DETAIL = 3000;
    private static final int MAX_PER_SOURCE = 2000;

    private final McpToolCatalog catalog;
    private final ModelRouter modelRouter;
    private final String model;
    private final int maxQueries;

    public AgentTaskAction(McpToolCatalog catalog,
                           ModelRouter modelRouter,
                           @Value("${app.task.agent.model:deepseek-chat}") String model,
                           @Value("${app.task.agent.max-queries:3}") int maxQueries) {
        this.catalog = catalog;
        this.modelRouter = modelRouter;
        this.model = model;
        this.maxQueries = Math.max(1, Math.min(maxQueries, 4));
    }

    @Override
    public String name() {
        return "agent";
    }

    @Override
    public String run(UserTask task, LocalDateTime now) {
        String goal = task.getRawInput() == null || task.getRawInput().isBlank()
                ? task.getTitle() : task.getRawInput();
        List<String> queries = planQueries(goal, now);
        List<String> sources = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        for (String q : queries) {
            try {
                String text = catalog.invoke("mcp_info_search",
                        "{\"q\":" + quote(q) + ",\"count\":5,\"fetch_top\":true}");
                if (text != null && !text.isBlank()) {
                    sources.add("【" + q + "】\n" + cut(text, MAX_PER_SOURCE));
                }
            } catch (RuntimeException e) {
                notes.add(q + " 没搜到（" + brief(e) + "）");
            }
        }
        if (sources.isEmpty()) {
            return "查询：" + String.join(" / ", queries)
                    + "\n\n没搜到可用资料" + (notes.isEmpty() ? "" : "（" + String.join("；", notes) + "）");
        }
        String answer;
        try {
            answer = summarize(goal, String.join("\n\n", sources), now);
        } catch (RuntimeException e) {
            answer = "（总结失败：" + brief(e) + "）\n" + cut(String.join("\n\n", sources), 1200);
        }
        return cut("查询：" + String.join(" / ", queries) + "\n\n" + answer, MAX_DETAIL);
    }

    /** 把一句话变成 1~3 条搜索词；模型失败就退回原句，至少还能搜一次。 */
    List<String> planQueries(String goal, LocalDateTime now) {
        String system = "你是搜索助手。把用户要查的事变成 " + maxQueries + " 条以内的中文搜索查询，"
                + "每条形如「关键词 关键词 时间」，要具体到能搜到结果（加上年份/赛事/地名）。"
                + "今天是 " + now.format(DATE) + "。只输出 JSON 数组，例如 [\"查询一\",\"查询二\"]，不要解释。";
        try {
            String out = modelRouter.client(model).prompt().system(system).user(goal).call().content();
            List<String> list = parseQueries(out);
            if (!list.isEmpty()) {
                return list;
            }
        } catch (RuntimeException e) {
            log.warn("AI 查询：生成搜索词失败，退回原句", e);
        }
        return List.of(goal);
    }

    List<String> parseQueries(String raw) {
        if (raw == null) {
            return List.of();
        }
        int start = raw.indexOf('[');
        int end = raw.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return List.of();
        }
        try {
            JsonNode arr = MAPPER.readTree(raw.substring(start, end + 1));
            List<String> list = new ArrayList<>();
            for (JsonNode n : arr) {
                String q = n.asText("").trim();
                if (!q.isEmpty() && list.size() < maxQueries) {
                    list.add(q);
                }
            }
            return list;
        } catch (Exception e) {
            return List.of();
        }
    }

    String summarize(String goal, String material, LocalDateTime now) {
        String system = "你要根据资料回答用户的问题。规则："
                + "1) 只写结论，150 字以内，不要复述资料原文；"
                + "2) 资料里没有的就明说「没查到」，不要猜；"
                + "3) 数字、日期、比分必须来自资料；"
                + "4) 最后另起一行写「来源：标题1、标题2」（最多两条）。"
                + "今天是 " + now.format(DATE) + "。";
        String user = "要查的事：" + goal + "\n\n资料：\n" + material;
        return modelRouter.client(model).prompt().system(system).user(user).call().content();
    }

    private static String quote(String s) {
        return MAPPER.valueToTree(s).toString();
    }

    private static String cut(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static String brief(Throwable e) {
        String m = e.getMessage();
        return m == null || m.isBlank() ? e.getClass().getSimpleName() : cut(m, 80);
    }
}