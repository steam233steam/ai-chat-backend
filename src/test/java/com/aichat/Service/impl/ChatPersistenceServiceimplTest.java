package com.aichat.Service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.aichat.Entity.ChatMessage;
import com.aichat.Mapper.ChatMessageMapper;
import com.aichat.Mapper.ChatSessionMapper;

class ChatPersistenceServiceimplTest {

    private ChatMessageMapper chatMessageMapper;
    private ChatSessionMapper chatSessionMapper;
    private ChatPersistenceServiceimpl service;

    @BeforeEach
    void setUp() {
        chatMessageMapper = mock(ChatMessageMapper.class);
        chatSessionMapper = mock(ChatSessionMapper.class);
        service = new ChatPersistenceServiceimpl();
        ReflectionTestUtils.setField(service, "chatMessageMapper", chatMessageMapper);
        ReflectionTestUtils.setField(service, "chatSessionMapper", chatSessionMapper);
    }

    @Test
    void shouldReturnLatestPageInAscendingOrder() {
        when(chatMessageMapper.selectLatestBySession("u1", "s1", 2))
                .thenReturn(List.of(
                        message(9L, "assistant", "九"),
                        message(8L, "user", "八")));

        List<ChatMessage> result = service.getMessages("u1", "s1", null, 2);

        assertThat(result).extracting(ChatMessage::getId).containsExactly(8L, 9L);
    }

    @Test
    void shouldUseCursorQueryWhenBeforeIdProvided() {
        when(chatMessageMapper.selectBySessionBeforeId("u1", "s1", 10L, 2))
                .thenReturn(List.of(
                        message(9L, "assistant", "九"),
                        message(8L, "user", "八")));

        List<ChatMessage> result = service.getMessages("u1", "s1", 10L, 2);

        assertThat(result).extracting(ChatMessage::getId).containsExactly(8L, 9L);
        verify(chatMessageMapper).selectBySessionBeforeId("u1", "s1", 10L, 2);
    }

    @Test
    void shouldReturnSavedMessageWithGeneratedId() {
        ChatMessage input = message(0L, "user", "你好");
        input.setId(null);
        when(chatMessageMapper.insert(input)).thenAnswer(invocation -> {
            ChatMessage inserted = invocation.getArgument(0);
            inserted.setId(42L);
            return 1;
        });

        ChatMessage saved = service.saveMessage(input, "标题");

        assertThat(saved.getId()).isEqualTo(42L);
        verify(chatSessionMapper).upsert(any());
    }

    private ChatMessage message(long id, String role, String content) {
        ChatMessage message = new ChatMessage(
                "u1", "s1", role, content, LocalDateTime.of(2026, 10, 25, 12, 0));
        message.setId(id);
        return message;
    }
}
