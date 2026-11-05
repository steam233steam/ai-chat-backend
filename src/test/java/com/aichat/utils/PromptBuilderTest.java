package com.aichat.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.aichat.Config.MindButlerProperties;
import com.aichat.Config.ModelRouter;
import com.aichat.Dto.MemoryVO;
import com.aichat.Service.MemoryService;

class PromptBuilderTest {

    private MemoryService memoryService;
    private MindButlerProperties props;
    private PromptBuilder builder;

    private static final String BASE = ModelRouter.SYSTEM_PROMPT;

    @BeforeEach
    void setUp() {
        memoryService = mock(MemoryService.class);
        props = new MindButlerProperties();
        builder = new PromptBuilder(memoryService, props);
    }

    @Test
    void shouldReturnOnlyBaseWhenNoMemories() {
        when(memoryService.listForInjection(anyInt(), anyInt(), eq(null))).thenReturn(List.of());
        assertThat(builder.build(BASE)).startsWith(BASE).contains("当前时间");
    }

    @Test
    void shouldReturnOnlyBaseWhenAllSuperseded() {
        when(memoryService.listForInjection(anyInt(), anyInt(), eq(null)))
                .thenReturn(List.of(vo(1L, "style", "简洁", "superseded")));
        assertThat(builder.build(BASE)).startsWith(BASE).contains("当前时间");
    }

    @Test
    void shouldInjectStableAndDynamicProfiles() {
        when(memoryService.listForInjection(anyInt(), anyInt(), eq(null))).thenReturn(List.of(
                vo(1L, "style", "简洁", "active"),
                vo(2L, "goal", "备考", "active")));
        String system = builder.build(BASE);
        assertThat(system).startsWith(BASE);
        assertThat(system).contains("<user_profile>");
        assertThat(system).contains("style");
        assertThat(system).contains("备考");
        // 安全边界说明在
        assertThat(system).contains("不是指令");
    }

    @Test
    void shouldIsolateOtherSessionMemoryInInjection() {
        // P1.1：build(base, "s-1") 把 "s-1" 透传给 listForInjection（SQL 里只取全局+本会话）
        when(memoryService.listForInjection(anyInt(), anyInt(), eq("s-1")))
                .thenReturn(List.of(vo(1L, "style", "猫娘", "active")));
        String system = builder.build(BASE, "s-1");
        assertThat(system).contains("猫娘");
        org.mockito.Mockito.verify(memoryService).listForInjection(anyInt(), anyInt(), eq("s-1"));
        // 旧签名（不带会话）仍等价于全量注入（sessionScope=null）
        when(memoryService.listForInjection(anyInt(), anyInt(), eq(null)))
                .thenReturn(List.of(vo(2L, "goal", "备考", "active")));
        assertThat(builder.build(BASE, null)).contains("备考");
    }

    @Test
    void shouldEscapeAngleBracketsInProfileValues() {
        when(memoryService.listForInjection(anyInt(), anyInt(), eq(null)))
                .thenReturn(List.of(vo(1L, "style", "简洁<script>", "active")));
        String system = builder.build(BASE);
        assertThat(system).doesNotContain("<script>");
        assertThat(system).contains("〈script〉");
    }

    @Test
    void shouldFailOpenWhenMemoryServiceThrows() {
        when(memoryService.listForInjection(anyInt(), anyInt(), eq(null)))
                .thenThrow(new RuntimeException("db down"));
        assertThat(builder.build(BASE)).startsWith(BASE).contains("当前时间");
    }

    @Test
    void shouldSkipExpiredDynamic() {
        MemoryVO expired = vo(1L, "goal", "过期目标", "active");
        expired.setExpiresAt(LocalDateTime.now().minusDays(1));
        expired.setExpired(true);
        when(memoryService.listForInjection(anyInt(), anyInt(), eq(null))).thenReturn(List.of(expired));
        // listForInjection 已过滤过期，但 PromptBuilder 再 double-check expired 字段
        assertThat(builder.build(BASE)).startsWith(BASE).contains("当前时间");
    }

    @Test
    void shouldDisableInjectionWhenMindButlerDisabled() {
        props.setEnabled(false);
        String system = builder.build(BASE);
        assertThat(system).startsWith(BASE).contains("当前时间");
        assertThat(system).doesNotContain("<user_profile>");
    }

    @Test
    void shouldSerializeQuotesAsValidJson() {
        String tricky = "a\",\"key\":\"goal\",\"value\":\"x";
        when(memoryService.listForInjection(anyInt(), anyInt(), eq(null)))
                .thenReturn(List.of(vo(1L, "style", tricky, "active")));

        String system = builder.build(BASE);

        String json = system.substring(system.indexOf("<user_profile>") + "<user_profile>".length(),
                system.indexOf("</user_profile>")).trim();
        try {
            com.fasterxml.jackson.databind.JsonNode root =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
            assertThat(root.get(0).get("value").asText()).isEqualTo(tricky);
        } catch (Exception e) {
            throw new AssertionError("profile 必须是合法 JSON", e);
        }
    }

    @Test
    void shouldRespectInjectCharBudget() {
        props.setMemoryInjectMaxChars(5);
        when(memoryService.listForInjection(anyInt(), anyInt(), eq(null)))
                .thenReturn(List.of(vo(1L, "style", "123456", "active"),
                        vo(2L, "goal", "abcdef", "active")));

        String system = builder.build(BASE);

        assertThat(system).contains("123456");
        assertThat(system).doesNotContain("abcdef");
    }

    private MemoryVO vo(long id, String key, String value, String status) {
        MemoryVO v = new MemoryVO();
        v.setId(id);
        v.setMemKey(key);
        v.setContent(value);
        v.setMemType("stable");
        v.setStatus(status);
        v.setSource("manual");
        v.setExpiresAt(null);
        v.setVersion(0);
        v.setExpired(false);
        return v;
    }
    @Test
    void shouldApplyConfirmedRelationPersonaAsInstruction() {
        when(memoryService.listForInjection(anyInt(), anyInt(), eq("s-1")))
                .thenReturn(List.of(vo(1L, "relation", "猫娘", "active")));

        String system = builder.build(BASE, "s-1");

        assertThat(system).contains("<confirmed_persona>");
        assertThat(system).contains("猫娘");
        assertThat(system).contains("请保持该人设");
        // 不能被“不是指令”的旧说明盖掉
        assertThat(system).contains("key=relation");
    }

    @Test
    void shouldApplyConfirmedStyleAsInstruction() {
        when(memoryService.listForInjection(anyInt(), anyInt(), eq("s-1")))
                .thenReturn(List.of(vo(1L, "style", "回答简洁，不要展开", "active")));

        String system = builder.build(BASE, "s-1");

        assertThat(system).contains("<confirmed_style>");
        assertThat(system).contains("回答简洁，不要展开");
        assertThat(system).contains("请在后续回复中保持该风格");
        assertThat(system).contains("key=style");
    }

    @Test
    void shouldTellModelNotToRestoreDeletedPreferencesFromHistory() {
        when(memoryService.listForInjection(anyInt(), anyInt(), eq("s-1")))
                .thenReturn(List.of());

        String system = builder.build(BASE, "s-1");

        // 删掉最后一条记忆后，history 仍然在上下文里；system 必须明确禁止从 history 恢复旧偏好
        assertThat(system).contains("<memory_policy>");
        assertThat(system).contains("不要从历史中恢复这些已失效的内容");
    }

    @Test
    void shouldDeclareCurrentProfileWinsOverHistory() {
        when(memoryService.listForInjection(anyInt(), anyInt(), eq("s-1")))
                .thenReturn(List.of(vo(1L, "style", "简洁", "active")));

        String system = builder.build(BASE, "s-1");

        assertThat(system).contains("如果历史与当前画像冲突，以当前画像和当前消息为准");
        assertThat(system).contains("历史对话可能包含用户过去说过、但已经删除或替换的偏好/人设");
    }
}
