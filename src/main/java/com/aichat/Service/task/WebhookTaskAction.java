package com.aichat.Service.task;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.aichat.Entity.UserTask;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 把"闹钟响了"这件事推到外部地址（企业微信/钉钉/飞书群机器人、自建 webhook 都行）。
 *
 * 只 POST 一个 JSON，不强依赖对方返回什么；2xx 算成功，其余抛异常让调度器记 failed。
 * 地址来自 tb_user_task.action_config：{"url":"https://..."}。
 */
@Component
public class WebhookTaskAction implements TaskAction {

    private static final Logger log = LoggerFactory.getLogger(WebhookTaskAction.class);

    private final ObjectMapper mapper;
    private final HttpClient client;

    public WebhookTaskAction(ObjectMapper mapper) {
        this.mapper = mapper;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
    }

    @Override
    public String name() {
        return "webhook";
    }

    @Override
    public String run(UserTask task, LocalDateTime now) throws Exception {
        String url = urlOf(task);
        if (url == null) {
            throw new IllegalStateException("action_config 里没有 url");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("taskId", task.getId());
        body.put("title", task.getTitle());
        body.put("dueAt", task.getDueAt() == null ? null : task.getDueAt().toString());
        body.put("firedAt", now.toString());

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("webhook 返回 " + response.statusCode());
        }
        log.info("任务 webhook 已推送 taskId={} status={}", task.getId(), response.statusCode());
        return "webhook 已推送（" + response.statusCode() + "）";
    }

    private String urlOf(UserTask task) {
        String config = task.getActionConfig();
        if (config == null || config.isBlank()) {
            return null;
        }
        try {
            String url = mapper.readTree(config).path("url").asText("");
            return url.isBlank() ? null : url;
        } catch (Exception e) {
            log.warn("action_config 解析失败 taskId={}：{}", task.getId(), e.getMessage());
            return null;
        }
    }
}
