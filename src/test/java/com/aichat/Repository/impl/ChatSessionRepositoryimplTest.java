package com.aichat.Repository.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.test.util.ReflectionTestUtils;

import com.aichat.Dto.SMessage;
import com.aichat.Entity.ChatMessage;
import com.aichat.Entity.ChatSession;
import com.aichat.Service.ChatPersistenceService;

class ChatSessionRepositoryimplTest {

    private RedisTemplate<String, Object> redisTemplate;
    private ListOperations<String, Object> listOperations;
    private ZSetOperations<String, Object> zSetOperations;
    private HashOperations<String, Object, Object> hashOperations;
    private ChatPersistenceService chatPersistenceService;
    private ChatSessionRepositoryimpl repository;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(RedisTemplate.class);
        listOperations = mock(ListOperations.class);
        zSetOperations = mock(ZSetOperations.class);
        hashOperations = mock(HashOperations.class);
        chatPersistenceService = mock(ChatPersistenceService.class);

        when(redisTemplate.opsForList()).thenReturn(listOperations);
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);

        repository = new ChatSessionRepositoryimpl();
        ReflectionTestUtils.setField(repository, "redisTemplate", redisTemplate);
        ReflectionTestUtils.setField(repository, "chatPersistenceService", chatPersistenceService);
    }

    @Test
    void shouldLoadMessagesFromMysqlAndRefillRedisWhenCacheMiss() {
        String key = "aichat:chat:session:u1:s1";
        when(listOperations.range(key, 0, -1)).thenReturn(List.of());
        when(chatPersistenceService.getMessages("u1", "s1"))
                .thenReturn(List.of(new ChatMessage(
                        "u1", "s1", "user", "你好",
                        LocalDateTime.of(2026, 10, 22, 10, 0))));

        List<SMessage> history = repository.getMessageHistory("u1", "s1");

        assertThat(history).hasSize(1);
        assertThat(history.get(0).getText()).isEqualTo("你好");
        assertThat(history.get(0).getType()).isEqualTo("user");
        verify(chatPersistenceService).getMessages("u1", "s1");
        verify(listOperations).rightPushAll(eq(key), any(Object[].class));
        verify(redisTemplate).expire(eq(key), any(Duration.class));
    }

    @Test
    void shouldLoadTitleFromMysqlAndRefillRedis() {
        String titleKey = "aichat:chat:title:u1:s1";
        when(hashOperations.get(titleKey, "s1")).thenReturn(null);
        when(chatPersistenceService.getTitle("u1", "s1")).thenReturn("会话标题");

        String title = repository.getTitle("u1", "s1");

        assertThat(title).isEqualTo("会话标题");
        verify(hashOperations).put(titleKey, "s1", "会话标题");
        verify(redisTemplate).expire(eq(titleKey), any(Duration.class));
    }

    @Test
    void shouldLoadSessionsFromMysqlAndRefillRedisZSet() {
        String sessionsKey = "aichat:chat:sessions:u1";
        when(zSetOperations.reverseRange(sessionsKey, 0, -1)).thenReturn(Set.of());
        when(chatPersistenceService.getSessions("u1"))
                .thenReturn(List.of(new ChatSession(
                        "u1", "s1", "会话标题",
                        LocalDateTime.of(2026, 10, 22, 10, 0),
                        LocalDateTime.of(2026, 10, 22, 10, 5))));

        List<String> sessions = repository.getSessions("u1");

        assertThat(sessions).containsExactly("s1");
        long expectedScore = LocalDateTime.of(2026, 10, 22, 10, 5)
                .atZone(ZoneId.systemDefault())
                .toEpochSecond();
        verify(zSetOperations).add(sessionsKey, "s1", (double) expectedScore);
        verify(redisTemplate).expire(eq(sessionsKey), any(Duration.class));
    }

    @Test
    void shouldPersistMessageBeforeUpdatingRedisCache() {
        String messageKey = "aichat:chat:session:u1:s1";
        String titleKey = "aichat:chat:title:u1:s1";
        when(hashOperations.get(titleKey, "s1")).thenReturn(null);
        when(chatPersistenceService.getTitle("u1", "s1")).thenReturn(null);

        repository.save("u1", "s1", new UserMessage("新问题"));

        InOrder order = inOrder(chatPersistenceService, listOperations);
        order.verify(chatPersistenceService).saveMessage(any(ChatMessage.class), isNull());
        order.verify(listOperations).rightPush(eq(messageKey), any(SMessage.class));
    }

    @Test
    void shouldDeleteMysqlAndRedisWhenClearSession() {
        when(chatPersistenceService.clear("u1", "s1")).thenReturn(true);
        when(redisTemplate.delete(anyString())).thenReturn(true);

        boolean cleared = repository.clear("u1", "s1");

        assertThat(cleared).isTrue();
        verify(chatPersistenceService).clear("u1", "s1");
        verify(zSetOperations).remove("aichat:chat:sessions:u1", "s1");
        verify(redisTemplate).delete("aichat:chat:session:u1:s1");
    }
}
