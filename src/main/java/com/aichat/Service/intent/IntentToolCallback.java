package com.aichat.Service.intent;

import java.util.function.Function;

import org.springframework.ai.model.function.FunctionCallback;

/**
 * 把「内置动作」包装成模型能调的工具。
 *
 * <p>与 MCP 工具不同，这里的工具不访问外部服务，只把模型给的参数收进 {@link IntentPlan}，
 * 所以执行一定成功——失败也只可能是参数解析失败，那时返回一段说明让模型自己改。
 */
public class IntentToolCallback implements FunctionCallback {

    private final String name;
    private final String description;
    private final String inputSchema;
    private final Function<String, String> handler;

    public IntentToolCallback(String name, String description, String inputSchema,
            Function<String, String> handler) {
        this.name = name;
        this.description = description;
        this.inputSchema = inputSchema;
        this.handler = handler;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public String getInputTypeSchema() {
        return inputSchema;
    }

    @Override
    public String call(String toolInput) {
        try {
            return handler.apply(toolInput == null || toolInput.isBlank() ? "{}" : toolInput);
        } catch (RuntimeException e) {
            // 参数不合法时让模型重试，而不是把整轮对话变成 500
            return "参数不合法：" + e.getMessage();
        }
    }
}
