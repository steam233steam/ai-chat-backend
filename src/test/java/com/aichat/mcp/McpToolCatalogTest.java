package com.aichat.mcp;

import java.util.List;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 在真实 Spring 上下文里验证：配置能绑定、服务端进程能拉起、工具名能注册、调用能拿到文本。
 *
 * <p>这里不碰模型，只验证「Java 侧到 MCP 服务端」这一段；模型侧的函数调用由手工演示覆盖。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class McpToolCatalogTest {

    @Autowired
    private McpToolCatalog catalog;

    @Test
    void registersToolsFromConfiguredServerAndInvokesThem() {
        List<McpToolCatalog.McpToolInfo> tools = catalog.toolInfos();
        Assumptions.assumeFalse(tools.isEmpty(), "本机没有 node 或服务端没起来，跳过");

        assertTrue(tools.stream().anyMatch(t -> t.name().endsWith("_now")),
                "应该注册了 info 服务的 now 工具，实际: " + tools);
        String nowTool = tools.stream().filter(t -> t.name().endsWith("_now")).findFirst().orElseThrow().name();

        String text = catalog.invoke(nowTool, "{\"tz\":\"UTC\"}");
        assertFalse(text.isBlank(), "now 应返回非空文本");
    }

    @Test
    void emptyModelWhitelistHitOnlyForConfiguredModels() {
        // 白名单默认只含 deepseek-chat：本地模型不该被挂工具，否则提示词会被塞一堆用不上的 schema
        Assumptions.assumeFalse(catalog.toolInfos().isEmpty(), "服务端没起来，跳过");
        assertTrue(catalog.callbacksFor("deepseek-chat").size() > 0);
        assertTrue(catalog.callbacksFor("qwen2.5-coder:7b").isEmpty());
    }
}
