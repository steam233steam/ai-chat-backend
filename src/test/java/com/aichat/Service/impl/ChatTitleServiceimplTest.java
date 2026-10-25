package com.aichat.Service.impl;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClient.CallResponseSpec;
import org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec;
import org.springframework.test.util.ReflectionTestUtils;

import com.aichat.Repository.ChatSessionRepository;

class ChatTitleServiceimplTest {

    private ChatSessionRepository repository;
    private ChatClientRequestSpec requestSpec;
    private CallResponseSpec callResponseSpec;
    private ChatTitleServiceimpl service;

    @BeforeEach
    void setUp() {
        repository = mock(ChatSessionRepository.class);

        ChatClient chatClient = mock(ChatClient.class);
        requestSpec = mock(ChatClientRequestSpec.class);
        callResponseSpec = mock(CallResponseSpec.class);

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);

        service = new ChatTitleServiceimpl();
        ReflectionTestUtils.setField(service, "chatClient", chatClient);
        ReflectionTestUtils.setField(service, "chatSessionRepository", repository);
    }

    @Test
    void shouldSaveAiTitleWhenTitleIsStillFallback() {
        when(callResponseSpec.content()).thenReturn("  Java 集合面试题  ");
        when(repository.getTitle("u1", "s1")).thenReturn("请帮我梳理一下 Java 集合");

        service.generateTitleAsync("u1", "s1", "请帮我梳理一下 Java 集合", "请帮我梳理一下 Java 集合");

        verify(repository).saveTitle("u1", "s1", "Java 集合面试题");
    }

    @Test
    void shouldNotOverwriteTitleRenamedByUser() {
        when(callResponseSpec.content()).thenReturn("AI 标题");
        when(repository.getTitle("u1", "s1")).thenReturn("我自己改的标题");

        service.generateTitleAsync("u1", "s1", "问题", "问题");

        verify(repository, never()).saveTitle(anyString(), anyString(), anyString());
    }

    @Test
    void shouldKeepFallbackTitleWhenAiCallFails() {
        when(requestSpec.call()).thenThrow(new IllegalStateException("ollama down"));

        assertThatCode(() -> service.generateTitleAsync("u1", "s1", "问题", "问题"))
                .doesNotThrowAnyException();

        verify(repository, never()).getTitle(anyString(), anyString());
        verify(repository, never()).saveTitle(anyString(), anyString(), anyString());
    }
}
