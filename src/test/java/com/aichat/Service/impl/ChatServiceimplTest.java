package com.aichat.Service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClient.CallResponseSpec;
import org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.test.util.ReflectionTestUtils;

import com.aichat.Dto.UserDto;
import com.aichat.Repository.ChatSessionRepository;
import com.aichat.Service.ChatTitleService;
import com.aichat.utils.UserHolder;

/**
 * 回归测试：同步对话不能把当前问题发送两次
 */
class ChatServiceimplTest {

    private ChatSessionRepository repository;
    private ChatTitleService chatTitleService;
    private ChatClientRequestSpec requestSpec;
    private ChatServiceimpl service;

    @BeforeEach
    void setUp() {
        repository = mock(ChatSessionRepository.class);
        chatTitleService = mock(ChatTitleService.class);

        ChatClient chatClient = mock(ChatClient.class);
        requestSpec = mock(ChatClientRequestSpec.class);
        CallResponseSpec callResponseSpec = mock(CallResponseSpec.class);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.messages(anyList())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.content()).thenReturn("你好");

        service = new ChatServiceimpl();
        ReflectionTestUtils.setField(service, "chatSessionRepository", repository);
        ReflectionTestUtils.setField(service, "chatClient", chatClient);
        ReflectionTestUtils.setField(service, "chatTitleService", chatTitleService);

        UserDto user = new UserDto();
        user.setId("u1");
        user.setPhone("13800000000");
        UserHolder.saveUser(user);
    }

    @AfterEach
    void tearDown() {
        UserHolder.removeUser();
    }

    @Test
    void shouldSendHistoryOnlyOnceAndSaveAssistantReply() {
        List<Message> history = List.of(new UserMessage("上一轮"), new AssistantMessage("上一轮回复"));
        when(repository.getHistory("u1", "s1")).thenReturn(history);

        String answer = service.sendSession("s1", new UserMessage("新问题"));

        assertThat(answer).isEqualTo("你好");

        // 当前问题只通过 user(question) 进入 Prompt，不能同时出现在 messages 里
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Message>> historyCaptor = ArgumentCaptor.forClass(List.class);
        verify(requestSpec).messages(historyCaptor.capture());
        assertThat(historyCaptor.getValue()).containsExactlyElementsOf(history);

        verify(requestSpec).user("新问题");
        verify(repository).save(eq("u1"), eq("s1"), eq(new UserMessage("新问题")));
        verify(repository).save("u1", "s1", new AssistantMessage("你好"));
        // 已有历史时不应再生成标题
        verifyNoInteractions(chatTitleService);
    }

    @Test
    void shouldCreateTitleBeforeSavingFirstUserMessage() {
        when(repository.getHistory("u1", "s1")).thenReturn(List.of());

        service.sendSession("s1", new UserMessage("新问题"));

        InOrder order = inOrder(repository, chatTitleService);
        order.verify(repository).saveTitle("u1", "s1", "新问题");
        order.verify(chatTitleService).generateTitleAsync("u1", "s1", "新问题", "新问题");
        order.verify(repository).save(eq("u1"), eq("s1"), eq(new UserMessage("新问题")));
        order.verify(repository).save("u1", "s1", new AssistantMessage("你好"));
    }
}
