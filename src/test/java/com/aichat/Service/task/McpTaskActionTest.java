package com.aichat.Service.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import com.aichat.Entity.UserTask;
import com.aichat.mcp.McpToolCatalog;
import com.fasterxml.jackson.databind.ObjectMapper;

class McpTaskActionTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 8, 18, 0);

    private final McpToolCatalog catalog = mock(McpToolCatalog.class);
    private final McpTaskAction action = new McpTaskAction(catalog, new ObjectMapper());

    @Test
    void shouldReturnTitleWithToolResult() throws Exception {
        when(catalog.invoke(eq("mcp_info_weather"), anyString())).thenReturn("北京: 晴 +8°C");
        UserTask task = task("{\"tool\":\"mcp_info_weather\",\"args\":{\"city\":\"北京\"}}");

        String detail = action.run(task, NOW);

        assertThat(detail).isEqualTo("看天气：北京: 晴 +8°C");
        verify(catalog).ensureStarted();
    }

    @Test
    void shouldRejectMissingConfig() {
        assertThatThrownBy(() -> action.run(task(null), NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("action_config");
    }

    @Test
    void shouldRejectMissingTool() {
        assertThatThrownBy(() -> action.run(task("{\"args\":{}}"), NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tool");
    }

    @Test
    void shouldRejectToolWithoutMcpPrefix() {
        assertThatThrownBy(() -> action.run(task("{\"tool\":\"weather\"}"), NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mcp_");
    }

    @Test
    void shouldTruncateLongResult() throws Exception {
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            longText.append("很长的结果");
        }
        when(catalog.invoke(anyString(), anyString())).thenReturn(longText.toString());
        UserTask task = task("{\"tool\":\"mcp_info_search\",\"args\":{\"q\":\"考研\"}}");

        String detail = action.run(task, NOW);

        // 标题 + 冒号 + 300 字 + 省略号
        assertThat(detail.length()).isLessThanOrEqualTo("看天气：".length() + 301);
        assertThat(detail).endsWith("…");
    }

    private UserTask task(String actionConfig) {
        UserTask task = new UserTask();
        task.setId(31L);
        task.setTitle("看天气");
        task.setActionType("mcp");
        task.setActionConfig(actionConfig);
        return task;
    }
}
