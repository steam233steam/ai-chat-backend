package com.aichat.Service.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.aichat.Entity.UserTask;
import com.aichat.mcp.McpToolCatalog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class ChainTaskActionTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 11, 9, 0);

    private final McpToolCatalog catalog = mock(McpToolCatalog.class);
    private final PushTaskAction push = mock(PushTaskAction.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final ChainTaskAction action = new ChainTaskAction(catalog, push, mapper);

    @Test
    void shouldRunQueryThenPushWithResult() throws Exception {
        when(catalog.invoke(eq("mcp_info_search"), anyString())).thenReturn("S15 决赛 T1 3:2 KT");
        when(push.send(any(), anyString(), anyString(), any())).thenReturn("已推送到手机（serverchan，200）");
        UserTask task = task("""
                {"steps":[
                  {"id":"s1","type":"mcp","tool":"mcp_info_search","args":{"q":"LOL S赛 赛果"}},
                  {"id":"s2","type":"push","channel":"serverchan","sendkey":"SCT123456","content":"赛果：{{s1}}"}
                ]}""");

        String detail = action.run(task, NOW);

        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(push).send(any(JsonNode.class), eq("查赛果"), content.capture(), eq(7L));
        assertThat(content.getValue()).isEqualTo("赛果：S15 决赛 T1 3:2 KT");
        assertThat(detail).contains("s1").contains("s2").contains("S15 决赛");
    }

    @Test
    void shouldStopWhenStepFails() throws Exception {
        when(catalog.invoke(eq("mcp_info_search"), anyString()))
                .thenThrow(new RuntimeException("搜索超时"));
        UserTask task = task("""
                {"steps":[
                  {"id":"s1","type":"mcp","tool":"mcp_info_search","args":{"q":"x"}},
                  {"id":"s2","type":"push","channel":"serverchan","sendkey":"SCT123456","content":"{{s1}}"}
                ]}""");

        assertThatThrownBy(() -> action.run(task, NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("第 1 步")
                .hasMessageContaining("搜索超时");
        verify(push, never()).send(any(), anyString(), anyString(), any());
    }

    @Test
    void shouldRejectUnknownStepType() {
        UserTask task = task("{\"steps\":[{\"id\":\"s1\",\"type\":\"shell\",\"cmd\":\"rm -rf /\"}]}");

        assertThatThrownBy(() -> action.run(task, NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不支持的步骤类型");
    }

    @Test
    void shouldRejectEmptySteps() {
        assertThatThrownBy(() -> action.run(task("{\"steps\":[]}"), NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("没有配置步骤");
    }

    @Test
    void shouldTruncateLongReference() {
        String longText = "长".repeat(5000);

        String result = ChainTaskAction.interpolate("前{{s1}}后", Map.of("s1", longText));

        // 引用被截到 1000 字 + 省略号
        assertThat(result).startsWith("前长长长").endsWith("…后").hasSizeLessThan(1100);
    }

    private UserTask task(String config) {
        UserTask task = new UserTask();
        task.setId(7L);
        task.setTitle("查赛果");
        task.setActionType("chain");
        task.setActionConfig(config);
        return task;
    }
}
