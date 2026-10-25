package com.aichat.Config;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.aichat.Exception.BusinessException;

/**
 * 多模型路由器：按模型名决定用 Ollama（本地）还是 OpenAI 兼容协议（DeepSeek）。
 *
 * 为什么不用"每个供应商一个 ChatClient bean + @Qualifier"：
 * 两个 starter 的自动装配会产生多个 ChatModel bean，直接注入会歧义。
 * 这里把两个 ChatModel 都显式注入，手动建 ChatClient，按名字分发。
 */
@Component
public class ModelRouter {

    private static final String SYSTEM_PROMPT = """
            你是一个乐于助人的 AI 助手。
            回答要准确、简洁，优先使用中文；不确定时如实说明，不要编造事实。
            请结合对话上下文理解用户的问题。
            """;

    @Autowired
    private OllamaChatModel ollamaChatModel;

    @Autowired
    private OpenAiChatModel openAiChatModel;

    @Autowired
    private ModelProperties modelProperties;

    /** Ollama 的 ChatClient（带默认 system prompt），懒建复用 */
    private volatile ChatClient ollamaClient;

    /** OpenAI/DeepSeek 的 ChatClient（带默认 system prompt），懒建复用 */
    private volatile ChatClient openAiClient;

    /**
     * 按模型名取带默认 system prompt 的 ChatClient。
     * 模型名不在白名单时抛 400。
     *
     * @param modelName 前端传的模型名（null/空 时用默认模型）
     */
    public ChatClient client(String modelName) {
        String model = resolve(modelName);
        if (modelProperties.isOllamaModel(model)) {
            if (ollamaClient == null) {
                synchronized (this) {
                    if (ollamaClient == null) {
                        ollamaClient = ChatClient.builder(ollamaChatModel)
                                .defaultSystem(SYSTEM_PROMPT)
                                .build();
                    }
                }
            }
            return ollamaClient;
        } else {
            if (openAiClient == null) {
                synchronized (this) {
                    if (openAiClient == null) {
                        openAiClient = ChatClient.builder(openAiChatModel)
                                .defaultSystem(SYSTEM_PROMPT)
                                .build();
                    }
                }
            }
            return openAiClient;
        }
    }

    /** 白名单里的模型名（保持顺序去重），供 /chat/models 接口返回给前端。 */
    public Set<String> availableModels() {
        return Arrays.stream(modelProperties.getAvailable().split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** 校验 + 兜底：null/空 → 默认模型；不在白名单 → 抛 400（不静默兜底，避免用户以为切成功）。 */
    public String resolve(String modelName) {
        Set<String> whitelist = availableModels();
        String model;
        if (modelName == null || modelName.isBlank()) {
            model = modelProperties.getDefaultModel();
        } else {
            model = modelName.strip();
        }
        if (!whitelist.contains(model)) {
            throw new BusinessException(400, "不支持的模型: " + model);
        }
        return model;
    }
}
