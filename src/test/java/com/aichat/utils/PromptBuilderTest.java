package com.aichat.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
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
        when(memoryService.listForInjection(anyInt(), anyInt())).thenReturn(List.of());
        assertThat(builder.build(BASE)).isEqualTo(BASE);
    }

    @Test
    void shouldReturnOnlyBaseWhenAllSuperseded() {
        when(memoryService.listForInjection(anyInt(), anyInt()))
                .thenReturn(List.of(vo(1L, "style", "简洁", "superseded")));
        assertThat(builder.build(BASE)).isEqualTo(BASE);
    }

    @Test
    void shouldInjectStableAndDynamicProfiles() {
        when(memoryService.listForInjection(anyInt(), anyInt())).thenReturn(List.of(
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
    void shouldEscapeAngleBracketsInProfileValues() {
        when(memoryService.listForInjection(anyInt(), anyInt()))
                .thenReturn(List.of(vo(1L, "style", "简洁<script>", "active")));
        String system = builder.build(BASE);
        assertThat(system).doesNotContain("<script>");
        assertThat(system).contains("〈script〉");
    }

    @Test
    void shouldFailOpenWhenMemoryServiceThrows() {
        when(memoryService.listForInjection(anyInt(), anyInt()))
                .thenThrow(new RuntimeException("db down"));
        assertThat(builder.build(BASE)).isEqualTo(BASE);
    }

    @Test
    void shouldSkipExpiredDynamic() {
        MemoryVO expired = vo(1L, "goal", "过期目标", "active");
        expired.setExpiresAt(LocalDateTime.now().minusDays(1));
        expired.setExpired(true);
        when(memoryService.listForInjection(anyInt(), anyInt())).thenReturn(List.of(expired));
        // listForInjection 已过滤过期，但 PromptBuilder 再 double-check expired 字段
        assertThat(builder.build(BASE)).isEqualTo(BASE);
    }

    @Test
    void shouldDisableInjectionWhenMindButlerDisabled() {
        props.setEnabled(false);
        assertThat(builder.build(BASE)).isEqualTo(BASE);
    }

    @Test
    void shouldSerializeQuotesAsValidJson() {
        String tricky = "a\",\"key\":\"goal\",\"value\":\"x";
        when(memoryService.listForInjection(anyInt(), anyInt()))
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
        when(memoryService.listForInjection(anyInt(), anyInt()))
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
}
