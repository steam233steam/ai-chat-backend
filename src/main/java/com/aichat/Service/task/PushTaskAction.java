package com.aichat.Service.task;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.aichat.Entity.UserTask;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 到点往手机上推一条横幅通知。
 *
 * 不自己发推送（那要 APNs/FCM 和 App），而是借三个"已经装在手机上、有通知权限"的通道：
 * <ul>
 *   <li>ntfy：安卓，装一个开源 App 订阅主题即可，免费、无需注册</li>
 *   <li>bark：iOS，App Store 免费，一个 URL 就能推</li>
 *   <li>wecom：企业微信群机器人，装了企业微信就能收</li>
 * </ul>
 *
 * action_config 形如：
 * <pre>
 * {"channel":"ntfy","server":"https://ntfy.sh","topic":"mindbutler-233"}
 * {"channel":"bark","target":"https://api.day.app/你的KEY"}
 * {"channel":"wecom","target":"https://qyapi.weixin.qq.com/..."}
 * </pre>
 */
@Component
public class PushTaskAction implements TaskAction {

    private static final Logger log = LoggerFactory.getLogger(PushTaskAction.class);

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("M月d日 HH:mm");

    /** 允许的通道。 */
    public static final Set<String> CHANNELS = Set.of("ntfy", "bark", "wecom");

    private final ObjectMapper mapper;
    private final HttpClient client;

    public PushTaskAction(ObjectMapper mapper) {
        this.mapper = mapper;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
    }

    @Override
    public String name() {
        return "push";
    }

    @Override
    public String run(UserTask task, LocalDateTime now) throws Exception {
        JsonNode config = parseConfig(task);
        String channel = config.path("channel").asText("").trim().toLowerCase();
        String message = messageOf(task);
        String title = task.getTitle() == null ? "任务提醒" : task.getTitle();

        String url;
        Map<String, Object> payload;
        switch (channel) {
            case "ntfy" -> {
                String server = text(config, "server", "https://ntfy.sh");
                String topic = config.path("topic").asText("").trim();
                if (topic.isEmpty()) {
                    throw new IllegalStateException("ntfy 通道缺少 topic");
                }
                url = stripTrailingSlash(server);
                payload = ntfyPayload(topic, title, message);
            }
            case "bark" -> {
                String target = text(config, "target", "");
                if (target.isEmpty()) {
                    throw new IllegalStateException("bark 通道缺少 target（形如 https://api.day.app/你的KEY）");
                }
                url = target;
                payload = barkPayload(title, message);
            }
            case "wecom" -> {
                String target = text(config, "target", "");
                if (target.isEmpty()) {
                    throw new IllegalStateException("企业微信通道缺少 target（群机器人 webhook 地址）");
                }
                url = target;
                payload = wecomPayload(title, message);
            }
            default -> throw new IllegalStateException("不支持的推送通道：" + channel);
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(6))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("推送通道 " + channel + " 返回 " + response.statusCode());
        }
        log.info("任务推送已发送 taskId={} channel={} status={}", task.getId(), channel, response.statusCode());
        return "已推送到手机（" + channel + "，" + response.statusCode() + "）";
    }

    /** ntfy 的 JSON 发布格式；priority=4 才会以横幅形式弹出（3 只会进通知栏）。 */
    static Map<String, Object> ntfyPayload(String topic, String title, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("topic", topic);
        body.put("title", title);
        body.put("message", message);
        body.put("priority", 4);
        body.put("tags", List.of("alarm_clock"));
        return body;
    }

    /** Bark 的 JSON 推送格式。 */
    static Map<String, Object> barkPayload(String title, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", title);
        body.put("body", message);
        body.put("group", "MindButler");
        body.put("sound", "default");
        return body;
    }

    /** 企业微信群机器人的文本消息格式。 */
    static Map<String, Object> wecomPayload(String title, String message) {
        Map<String, Object> text = new LinkedHashMap<>();
        text.put("content", title + "\n" + message);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("msgtype", "text");
        body.put("text", text);
        return body;
    }

    /** 通知正文：把任务本身的信息写清楚，锁屏上一眼能看懂。 */
    static String messageOf(UserTask task) {
        StringBuilder sb = new StringBuilder("到点了");
        if (task.getDueAt() != null) {
            sb.append("，计划时间 ").append(task.getDueAt().format(TIME));
        }
        if ("daily".equals(task.getRecurrence())) {
            sb.append("（每天）");
        } else if ("weekly".equals(task.getRecurrence())) {
            sb.append("（每周）");
        }
        return sb.toString();
    }

    private JsonNode parseConfig(UserTask task) {
        String config = task.getActionConfig();
        if (config == null || config.isBlank()) {
            throw new IllegalStateException("action_config 为空，推送动作需要 channel 配置");
        }
        try {
            return mapper.readTree(config);
        } catch (Exception e) {
            throw new IllegalStateException("action_config 不是合法 JSON");
        }
    }

    private static String text(JsonNode node, String field, String fallback) {
        String value = node.path(field).asText("").trim();
        return value.isEmpty() ? fallback : value;
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
