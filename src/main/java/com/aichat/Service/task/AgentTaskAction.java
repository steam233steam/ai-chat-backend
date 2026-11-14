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
        String direct = directToolAnswer(goal);
        if (direct != null) {
            sources.add("【专用工具】\n" + direct);
        } else {
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
        // 自己核对：答案自己说没查到 / 置信度低，就补一次更「官方」的搜索再答一遍，只补一轮。
        if (needsSecondLook(answer)) {
            try {
                String extra = catalog.invoke("mcp_info_search",
                        "{\"q\":" + quote(goal + " 官方公告 最新") + ",\"count\":5,\"fetch_top\":true}");
                if (extra != null && !extra.isBlank()) {
                    sources.add("【补搜：" + goal + " 官方公告 最新】\n" + cut(extra, MAX_PER_SOURCE));
                    answer = summarize(goal, String.join("\n\n", sources), now);
                }
            } catch (RuntimeException e) {
                log.info("AI 查询：补搜没成功，保留初版结论（{}）", brief(e));
            }
        }
        return cut("查询：" + String.join(" / ", queries) + "\n\n" + answer, MAX_DETAIL);
    }

    /** 把一句话变成 1~3 条搜索词；模型失败就退回原句，至少还能搜一次。 */
    List<String> planQueries(String goal, LocalDateTime now) {
        String system = "你是搜索助手。把用户要查的事变成 " + maxQueries + " 条以内的中文搜索查询，"
                + "每条形如「关键词 关键词 时间」，要具体到能搜到结果（加上年份/赛事/地名）。"
                + "国际奖项、海外公司、科技产品这类主题，再加一条英文查询（例如 \"2026 Nobel Prize in Literature winner\"）。"
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

    /**
     * 天气、当前时间这类有专用工具的问题，直接调工具比让模型翻网页靠谱：
     * 网页天气预报经常抓不到正文，工具却是实时的。
     */
    private String directToolAnswer(String goal) {
        try {
            if (goal.matches("(?s).*(天气|气温|下雨|温度|几度).*")) {
                String city = cityOf(goal);
                return catalog.invoke("mcp_info_weather",
                        "{\"city\":" + quote(city) + "}");
            }
            if (goal.matches("(?s).*(几点|现在时间|今天几号|日期).*")) {
                return catalog.invoke("mcp_info_now", "{}");
            }
        } catch (RuntimeException e) {
            log.info("AI 查询：专用工具没调通（{}），继续走搜索", brief(e));
        }
        return null;
    }

    /** 从「明天北京天气怎么样」里抠出城市名，抠不到就给北京，总比报错强。 */
    String cityOf(String goal) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("([\u4e00-\u9fa5]{2,4}?)(?:市|区)?(?:的)?(?:今天|明天|后天)?(?:天气|气温|温度|下雨)")
                .matcher(goal);
        if (m.find()) {
            String city = m.group(1).replaceAll("^(今天|明天|后天|查一下|查下|看看|帮我)", "");
            if (!city.isEmpty()) {
                return city;
            }
        }
        return "北京";
    }

    /** 初版结论自己都不信（说没查到 / 低置信）时，值得再搜一次官方口径。 */
    boolean needsSecondLook(String answer) {
        if (answer == null) {
            return false;
        }
        return answer.contains("没查到") || answer.contains("核对：低") || answer.contains("未标注");
    }

    String summarize(String goal, String material, LocalDateTime now) {
        String system = "你是检索核对员。根据资料回答用户的问题，并且必须自己核对。规则："
                + "1) 只写结论，150 字以内，不复述原文；"
                + "2) 数字、日期、比分、人名必须能在资料里找到，找不到就不写；"
                + "3) 至少两条资料都提到的，标「多源」；只有一条提到的，标「单源」；两条资料互相矛盾时，"
                + "两边都写出来并注明哪条更新/更权威；"
                + "4) 资料不足以回答就直说「没查到」，不要用常识补；"
                + "5) 资料里出现「【专用工具】」时，那是实时查询结果（天气/时间），直接用它作答，"
                + "不要说「没查到具体日期」这类挑刺的话；"
                + "6) 严格输出 JSON，不要代码块："
                + "{\"answer\":\"...\",\"sources\":[\"标题1\",\"标题2\"],\"confidence\":\"高/中/低\",\"check\":\"用一句话说明你怎么核对的（几个来源一致、有无冲突）\"}。"
                + "今天是 " + now.format(DATE) + "。";
        String user = "要查的事：" + goal + "\n\n资料：\n" + material;
        String raw = modelRouter.client(model).prompt().system(system).user(user).call().content();
        return formatAnswer(raw);
    }

    /** 模型应该返回 JSON；万一它又写成人话，就按纯文本用，别把整条结果丢掉。 */
    String formatAnswer(String raw) {
        if (raw == null || raw.isBlank()) {
            return "（模型没有返回内容）";
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start >= 0 && end > start) {
            try {
                JsonNode node = MAPPER.readTree(raw.substring(start, end + 1));
                String answer = node.path("answer").asText("").trim();
                if (!answer.isEmpty()) {
                    StringBuilder sb = new StringBuilder(answer);
                    String confidence = node.path("confidence").asText("").trim();
                    String check = node.path("check").asText("").trim();
                    JsonNode sources = node.path("sources");
                    List<String> names = new ArrayList<>();
                    if (sources.isArray()) {
                        for (JsonNode s : sources) {
                            String v = s.asText("").trim();
                            if (!v.isEmpty() && names.size() < 3) {
                                names.add(v);
                            }
                        }
                    }
                    sb.append("\n\n核对：").append(confidence.isEmpty() ? "未标注" : confidence);
                    if (!check.isEmpty()) {
                        sb.append("（").append(cut(check, 60)).append("）");
                    }
                    if (!names.isEmpty()) {
                        sb.append("\n来源：").append(String.join("、", names));
                    } else {
                        sb.append("\n来源：未标注");
                    }
                    return sb.toString();
                }
            } catch (Exception e) {
                log.debug("AI 查询：总结结果不是合法 JSON，按纯文本处理");
            }
        }
        return cut(raw.trim(), MAX_DETAIL);
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