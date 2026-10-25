package com.aichat.Config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 多模型配置（application.properties 里 app.models.* 绑定到这里）。
 * 白名单 + 默认模型都从配置读，不硬编码在业务逻辑里。
 */
@Component
@ConfigurationProperties(prefix = "app.models")
public class ModelProperties {

    /** 可选模型白名单，逗号分隔。前端只能从这里选。 */
    private String available = "qwen2.5-coder:7b,deepseek-chat";

    /** 默认模型（前端没传 modelName 时用），必须在 available 里 */
    private String defaultModel = "qwen2.5-coder:7b";

    /** 哪些模型走 Ollama（本地），逗号分隔。剩余白名单模型默认走 OpenAI 兼容协议（DeepSeek）。 */
    private String ollamaModels = "qwen2.5-coder:7b";

    public String getAvailable() {
        return available;
    }

    public void setAvailable(String available) {
        this.available = available;
    }

    public String getDefaultModel() {
        return defaultModel;
    }

    public void setDefaultModel(String defaultModel) {
        this.defaultModel = defaultModel;
    }

    public String getOllamaModels() {
        return ollamaModels;
    }

    public void setOllamaModels(String ollamaModels) {
        this.ollamaModels = ollamaModels;
    }

    /** 判断某个模型是否属于 Ollama 本地 */
    public boolean isOllamaModel(String modelName) {
        if (ollamaModels == null || ollamaModels.isBlank()) {
            return false;
        }
        for (String m : ollamaModels.split(",")) {
            if (m.trim().equals(modelName)) {
                return true;
            }
        }
        return false;
    }
}
