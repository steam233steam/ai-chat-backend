package com.aichat.Service.support;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.test.util.ReflectionTestUtils;

import com.aichat.Dto.UserDto;
import com.aichat.Repository.ChatSessionRepository;
import com.aichat.Service.PersonaReplyService;
import com.aichat.utils.UserHolder;

class ConfirmationReceiptWriterTest {

    private ChatSessionRepository repository;
    private PersonaReplyService personaReplyService;
    private ConfirmationReceiptWriter writer;

    @BeforeEach
    void setUp() {
        repository = mock(ChatSessionRepository.class);
        personaReplyService = mock(PersonaReplyService.class);
        writer = new ConfirmationReceiptWriter(repository, personaReplyService);
        UserDto user = new UserDto();
        user.setId("u1");
        UserHolder.saveUser(user);
    }

    @AfterEach
    void tearDown() {
        UserHolder.removeUser();
    }

    @Test
    void shouldWritePersonaReceiptWithLockedModel() {
        when(personaReplyService.confirmed("s1")).thenReturn("已经定好了喵");
        when(repository.getModel("u1", "s1")).thenReturn("qwen2.5-coder:7b");

        writer.writeConfirmed("s1");

        verify(repository).save(eq("u1"), eq("s1"),
                any(AssistantMessage.class), eq("qwen2.5-coder:7b"));
    }

    @Test
    void shouldNotFailConfirmationWhenReceiptWriteFails() {
        when(personaReplyService.cancelled("s1")).thenReturn("计划取消喵");
        when(repository.getModel("u1", "s1")).thenReturn(null);
        doThrow(new RuntimeException("db down")).when(repository)
                .save(eq("u1"), eq("s1"), any(AssistantMessage.class), any());

        writer.writeCancelled("s1"); // 不能抛，不能影响已经完成的确认/取消
    }

    @Test
    void shouldIgnoreBlankSession() {
        writer.writeConfirmed("");
        verify(personaReplyService, never()).confirmed(anyString());
        verify(repository, never()).save(anyString(), anyString(), any(), any());
    }
}
