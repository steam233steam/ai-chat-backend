package com.aichat.Config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ChatClientConfig {

    /**
     * 全局系统提示词。统一放在这里，避免每个调用点各写一份、互相覆盖。
     */
    private static final String SYSTEM_PROMPT = """
            你是一个乐于助人的 AI 助手。
            回答要准确、简洁，优先使用中文；不确定时如实说明，不要编造事实。
            请结合对话上下文理解用户的问题。
            """;

    @Bean
    public ChatClient chatClient(ChatClient.Builder chatClientBuilder) {
        return chatClientBuilder
                .defaultSystem(SYSTEM_PROMPT)
                .build();
    }
}
