package com.aichat.Service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClient.CallResponseSpec;
import org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec;
import org.springframework.test.util.ReflectionTestUtils;

import com.aichat.Config.ModelRouter;
import com.aichat.Dto.UserDto;
import com.aichat.Repository.ChatSessionRepository;
import com.aichat.utils.PromptBuilder;
import com.aichat.utils.UserHolder;

class PersonaReplyServiceImplTest {

    private ModelRouter modelRouter;
    private PromptBuilder promptBuilder;
    private ChatSessionRepository repository;
    private ExecutorService executor;
    private CallResponseSpec callResponseSpec;
    private PersonaReplyServiceImpl service;

    @BeforeEach
    void setUp() {
        modelRouter = mock(ModelRouter.class);
        promptBuilder = mock(PromptBuilder.class);
        repository = mock(ChatSessionRepository.class);
        executor = Executors.newSingleThreadExecutor();

        ChatClient chatClient = mock(ChatClient.class);
        ChatClientRequestSpec requestSpec = mock(ChatClientRequestSpec.class);
        callResponseSpec = mock(CallResponseSpec.class);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(modelRouter.client("qwen2.5-coder:7b")).thenReturn(chatClient);
        when(promptBuilder.build(anyString(), eq("s1"))).thenReturn("system-persona");
        when(repository.getModel("u1", "s1")).thenReturn("qwen2.5-coder:7b");

        service = new PersonaReplyServiceImpl(modelRouter, promptBuilder, repository, executor);

        UserDto user = new UserDto();
        user.setId("u1");
        UserHolder.saveUser(user);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        UserHolder.removeUser();
    }

    @Test
    void shouldReturnPersonaGeneratedReceipt() {
        when(callResponseSpec.content()).thenReturn("已经定好了喵～");

        assertThat(service.confirmed("s1")).isEqualTo("已经定好了喵～");
    }

    @Test
    void shouldRejectMetaSystemSettingReplyAndUseFallback() {
        when(callResponseSpec.content()).thenReturn("刚才那条是系统设定说明，请放心");

        assertThat(service.confirmed("s1")).isEqualTo("已经定好");
    }

    @Test
    void shouldRejectMarkdownOrOverlongReply() {
        when(callResponseSpec.content()).thenReturn("```已经定好```");

        assertThat(service.cancelled("s1")).isEqualTo("计划取消");
    }

    @Test
    void shouldFallbackWhenModelCallFails() {
        when(callResponseSpec.content()).thenThrow(new RuntimeException("model down"));

        assertThat(service.confirmed("s1")).isEqualTo("已经定好");
    }
}
