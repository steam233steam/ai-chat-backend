package com.aichat.Service.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.aichat.Config.ModelRouter;
import com.aichat.Entity.UserTask;
import com.aichat.mcp.McpToolCatalog;
import org.springframework.ai.chat.client.ChatClient;

class AgentTaskActionTest {

    private final McpToolCatalog catalog = mock(McpToolCatalog.class);
    private final ModelRouter router = mock(ModelRouter.class, RETURNS_DEEP_STUBS);

    private AgentTaskAction action() {
        return new AgentTaskAction(catalog, router, "deepseek-chat", 3);
    }

    @Test
    void shouldParseQueriesFromModelOutput() {
        AgentTaskAction a = action();
        assertThat(a.parseQueries("[\"LOL 2026 全球总决赛 决赛 时间\",\"英雄联盟 S16 赛程 11月15日\"]"))
                .containsExactly("LOL 2026 全球总决赛 决赛 时间", "英雄联盟 S16 赛程 11月15日");
    }

    @Test
    void shouldIgnoreChatterAroundJsonArray() {
        AgentTaskAction a = action();
        assertThat(a.parseQueries("好的，我给出的查询是：[\"北京 天气 11月15日\"] 希望有帮助"))
                .containsExactly("北京 天气 11月15日");
    }

    @Test
    void shouldFallbackToRawGoalWhenModelOutputIsNotJson() {
        AgentTaskAction a = action();
        assertThat(a.parseQueries("我建议你直接搜北京天气")).isEmpty();
    }

    @Test
    void shouldSearchEveryPlannedQueryAndSummarize() {
        when(catalog.invoke(anyString(), anyString())).thenReturn("搜索「x」共 1 条：\n\n1. 标题\n   https://a.com\n   摘要");
        ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(router.client("deepseek-chat")).thenReturn(client);
        // 第一次调用是「生成搜索词」，第二次是「根据资料作答」
        when(client.prompt().system(anyString()).user(anyString()).call().content())
                .thenReturn("[\"LOL 2026 全球总决赛 决赛 时间\"]", "答案：11月15日凌晨3点决赛。\n来源：虎扑");

        UserTask task = new UserTask();
        task.setTitle("查 LOL 赛程");
        task.setRawInput("查一下明天LOL赛程");

        String detail = action().run(task, LocalDateTime.of(2026, 11, 14, 19, 0));
        assertThat(detail).contains("查询：").contains("答案").isNotBlank();
    }

    @Test
    void shouldReportNoMaterialWhenEveryQueryFails() {
        when(catalog.invoke(anyString(), anyString())).thenThrow(new RuntimeException("工具执行失败: 没查到"));
        UserTask task = new UserTask();
        task.setTitle("查 LOL 赛程");

        String detail = action().run(task, LocalDateTime.of(2026, 11, 14, 19, 0));
        assertThat(detail).contains("没搜到可用资料");
    }
}