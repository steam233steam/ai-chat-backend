package com.aichat.Config;

import org.springframework.context.annotation.Configuration;

/**
 * 多模型架构说明：
 * Ollama + OpenAI（DeepSeek）两个 starter 同时存在时，Spring AI 自动装配会产生
 * 多个 ChatModel / ChatClient.Builder bean，直接注入会报 NoUniqueBeanDefinition。
 * 因此不再用自动装配的单例 ChatClient，改为 ModelRouter 按模型名分发。
 * 系统提示词统一在 ModelRouter 构造 ChatClient 时注入，避免散落在各调用点。
 */
@Configuration
public class ChatClientConfig {
}
