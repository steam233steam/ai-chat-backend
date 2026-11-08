package com.aichat.Service.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.aichat.Entity.UserTask;
import com.fasterxml.jackson.databind.ObjectMapper;

class PushTaskActionTest {

    private final PushTaskAction action = new PushTaskAction(new ObjectMapper());

    @Test
    @DisplayName("ntfy 负载：带 topic/title/message，priority=4 才会弹横幅")
    void shouldBuildNtfyPayload() {
        Map<String, Object> payload = PushTaskAction.ntfyPayload("mindbutler-233", "交作业", "到点了");

        assertThat(payload).containsEntry("topic", "mindbutler-233")
                .containsEntry("title", "交作业")
                .containsEntry("message", "到点了")
                .containsEntry("priority", 4);
        assertThat(payload.get("tags")).isEqualTo(List.of("alarm_clock"));
    }

    @Test
    @DisplayName("bark 负载：title/body/group")
    void shouldBuildBarkPayload() {
        Map<String, Object> payload = PushTaskAction.barkPayload("交作业", "到点了");

        assertThat(payload).containsEntry("title", "交作业")
                .containsEntry("body", "到点了")
                .containsEntry("group", "MindButler");
    }

    @Test
    @DisplayName("企业微信负载：text 消息把标题和正文拼在一起")
    void shouldBuildWecomPayload() {
        Map<String, Object> payload = PushTaskAction.wecomPayload("交作业", "到点了");

        assertThat(payload).containsEntry("msgtype", "text");
        @SuppressWarnings("unchecked")
        Map<String, Object> text = (Map<String, Object>) payload.get("text");
        assertThat(text.get("content")).isEqualTo("交作业\n到点了");
    }

    @Test
    @DisplayName("通知正文带上计划时间与重复规则")
    void shouldDescribeDueTimeAndRecurrence() {
        UserTask task = new UserTask();
        task.setTitle("交作业");
        task.setDueAt(LocalDateTime.of(2026, 11, 9, 9, 0));
        task.setRecurrence("daily");

        String message = PushTaskAction.messageOf(task);

        assertThat(message).contains("11月9日 09:00").contains("每天");
    }

    @Test
    @DisplayName("没有配置就执行：抛异常让调度器记 failed")
    void shouldFailFastWhenConfigMissing() {
        UserTask task = new UserTask();
        task.setTitle("交作业");

        assertThatThrownBy(() -> action.run(task, LocalDateTime.now()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("action_config");
    }

    @Test
    @DisplayName("不支持的通道：抛异常")
    void shouldRejectUnknownChannel() {
        UserTask task = new UserTask();
        task.setTitle("交作业");
        task.setActionConfig("{\"channel\":\"telegram\"}");

        assertThatThrownBy(() -> action.run(task, LocalDateTime.now()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不支持的推送通道");
    }

    @Test
    @DisplayName("动作名固定为 push，调度器按它查表")
    void shouldExposeActionName() {
        assertThat(action.name()).isEqualTo("push");
        assertThat(PushTaskAction.CHANNELS)
                .containsExactlyInAnyOrder("ntfy", "bark", "wecom", "serverchan", "pushplus");
    }

    @Test
    @DisplayName("Server酱（微信）：表单报文只有 title 与 desp，且做 URL 编码")
    void shouldBuildServerChanForm() {
        Map<String, Object> payload = PushTaskAction.serverChanPayload("交作业", "到点了，计划时间 11月9日 09:00");

        assertThat(payload).containsEntry("title", "交作业");
        String form = PushTaskAction.encodeForm(payload);
        assertThat(form).startsWith("title=").contains("&desp=");
        assertThat(form).doesNotContain(" ").doesNotContain("，");
    }

    @Test
    @DisplayName("PushPlus：JSON 报文带 token/title/content/template")
    void shouldBuildPushPlusPayload() {
        Map<String, Object> payload = PushTaskAction.pushPlusPayload("tok123", "交作业", "到点了");

        assertThat(payload).containsEntry("token", "tok123")
                .containsEntry("title", "交作业")
                .containsEntry("content", "到点了")
                .containsEntry("template", "txt");
    }

    @Test
    @DisplayName("业务码非 0/200 视为失败（Server酱、PushPlus 都是 HTTP 200 包错误）")
    void shouldThrowOnBusinessErrorCode() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();

        assertThatThrownBy(() -> PushTaskAction.assertBusinessOk("serverchan",
                mapper.readTree("{\"code\":40001,\"message\":\"bad sendkey\"}")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("40001")
                .hasMessageContaining("bad sendkey");

        // code=0 与 code=200 都算成功
        PushTaskAction.assertBusinessOk("serverchan", mapper.readTree("{\"code\":0}"));
        PushTaskAction.assertBusinessOk("pushplus", mapper.readTree("{\"code\":200}"));
    }

    @Test
    @DisplayName("Server酱缺少 sendkey：抛异常")
    void shouldRejectServerChanWithoutKey() {
        UserTask task = new UserTask();
        task.setTitle("交作业");
        task.setActionConfig("{\"channel\":\"serverchan\"}");

        assertThatThrownBy(() -> action.run(task, LocalDateTime.now()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sendkey");
    }
}
