package com.aichat.Repository.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
                .thenReturn(List.of(message(11L, "user", "你好",
                        LocalDateTime.of(2026, 10, 22, 10, 0))));

        List<SMessage> history = repository.getMessageHistory("u1", "s1");

        assertThat(history).hasSize(1);
        assertThat(history.get(0).getId()).isEqualTo(11L);
        assertThat(history.get(0).getText()).isEqualTo("你好");
        assertThat(history.get(0).getType()).isEqualTo("user");
        verify(chatPersistenceService).getMessages("u1", "s1");
        verify(redisTemplate).delete(key);
        verify(listOperations).rightPushAll(eq(key), any(Object[].class));
        verify(redisTemplate).expire(eq(key), any(Duration.class));
    }

    @Test
    void shouldLoadTitleFromMysqlAndRefillRedis() {
        // 标题 hash key 只到 userId（与 model 缓存结构一致），field 用 sessionid
        String titleKey = "aichat:chat:title:u1";
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
        String titleKey = "aichat:chat:title:u1";
        when(hashOperations.get(titleKey, "s1")).thenReturn(null);
        when(chatPersistenceService.getTitle("u1", "s1")).thenReturn(null);
        ChatMessage persisted = new ChatMessage("u1", "s1", "user", "新问题", "qwen2.5-coder:7b",
                LocalDateTime.of(2026, 10, 25, 17, 0));
        persisted.setId(7L);
        when(chatPersistenceService.saveMessage(any(ChatMessage.class), isNull()))
                .thenReturn(persisted);

        repository.save("u1", "s1", new UserMessage("新问题"), "qwen2.5-coder:7b");

        InOrder order = inOrder(chatPersistenceService, listOperations);
        ArgumentCaptor<ChatMessage> dbMessageCaptor = ArgumentCaptor.forClass(ChatMessage.class);
        order.verify(chatPersistenceService).saveMessage(dbMessageCaptor.capture(), isNull());
        ArgumentCaptor<SMessage> messageCaptor = ArgumentCaptor.forClass(SMessage.class);
        order.verify(listOperations).rightPush(eq(messageKey), messageCaptor.capture());
        assertThat(dbMessageCaptor.getValue().getModel()).isEqualTo("qwen2.5-coder:7b");
        assertThat(messageCaptor.getValue().getId()).isEqualTo(7L);
        assertThat(messageCaptor.getValue().getModel()).isEqualTo("qwen2.5-coder:7b");
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

    @Test
    void shouldPageFromCacheWithoutTouchingMysql() {
        String key = "aichat:chat:session:u1:s1";
        when(listOperations.range(key, 0, -1)).thenReturn(List.of(
                cached(1L, "user", "一", 1000L),
                cached(2L, "assistant", "二", 2000L),
                cached(3L, "user", "三", 3000L)));

        List<SMessage> page = repository.getMessageHistory("u1", "s1", 3L, 1);

        assertThat(page).extracting(SMessage::getId).containsExactly(2L);
        verifyNoInteractions(chatPersistenceService);
    }

    @Test
    void shouldPageFromMysqlWithoutCachingPartialHistory() {
        String key = "aichat:chat:session:u1:s1";
        when(listOperations.range(key, 0, -1)).thenReturn(List.of());
        when(chatPersistenceService.getMessages("u1", "s1", 10L, 2))
                .thenReturn(List.of(
                        message(8L, "user", "八", LocalDateTime.of(2026, 10, 25, 10, 0)),
                        message(9L, "assistant", "九", LocalDateTime.of(2026, 10, 25, 10, 1))));

        List<SMessage> page = repository.getMessageHistory("u1", "s1", 10L, 2);

        assertThat(page).extracting(SMessage::getId).containsExactly(8L, 9L);
        verify(chatPersistenceService).getMessages("u1", "s1", 10L, 2);
        verify(listOperations, never()).rightPushAll(eq(key), any(Object[].class));
    }

    @Test
    void shouldRebuildFullCacheWhenCachedMessagesHaveNoId() {
        String key = "aichat:chat:session:u1:s1";
        // 旧版缓存没有 id，无法做游标分页，必须回源重建
        when(listOperations.range(key, 0, -1)).thenReturn(List.of(
                new SMessage("旧缓存消息", "user", 1000L)));
        when(chatPersistenceService.getMessages("u1", "s1"))
                .thenReturn(List.of(message(5L, "user", "数据库消息",
                        LocalDateTime.of(2026, 10, 25, 11, 0))));

        List<SMessage> history = repository.getMessageHistory("u1", "s1");

        assertThat(history).extracting(SMessage::getId).containsExactly(5L);
        InOrder order = inOrder(redisTemplate, listOperations);
        order.verify(redisTemplate).delete(key);
        order.verify(listOperations).rightPushAll(eq(key), any(Object[].class));
    }

    @Test
    void shouldServeCachedHistoryWithNullModelForLegacyMessages() {
        String key = "aichat:chat:session:u1:s1";
        // 加 model 列之前的历史消息 model 就是 null；这是合法缓存，不应反复回源
        when(listOperations.range(key, 0, -1)).thenReturn(List.of(
                new SMessage(5L, "历史消息", "user", null, 1000L)));

        List<SMessage> history = repository.getMessageHistory("u1", "s1");

        assertThat(history).extracting(SMessage::getId).containsExactly(5L);
        assertThat(history.get(0).getModel()).isNull();
        verifyNoInteractions(chatPersistenceService);
    }

    @Test
    void shouldSortCachedMessagesByIdToTolerateConcurrentWrites() {
        String key = "aichat:chat:session:u1:s1";
        when(listOperations.range(key, 0, -1)).thenReturn(List.of(
                cached(2L, "assistant", "二", 2000L),
                cached(1L, "user", "一", 1000L)));

        List<SMessage> history = repository.getMessageHistory("u1", "s1");

        assertThat(history).extracting(SMessage::getId).containsExactly(1L, 2L);
    }

    private ChatMessage message(long id, String role, String content, LocalDateTime time) {
        ChatMessage message = new ChatMessage("u1", "s1", role, content, time);
        message.setId(id);
        return message;
    }

    private SMessage cached(long id, String type, String content, long timestamp) {
        // 构造带 id + model 的新格式缓存，确保不会触发回源
        return new SMessage(id, content, type, "qwen2.5-coder:7b", timestamp);
    }

    @Test
    void shouldCacheMysqlAuthoritativeModelWhenLocking() {
        // 并发首条消息：本次请求 deepseek，但库里已被先到的 qwen 锁定（upsert 的 COALESCE 只认先到的）
        when(chatPersistenceService.getSessionModel("u1", "s1")).thenReturn("qwen2.5-coder:7b");

        repository.lockModel("u1", "s1", "deepseek-chat");

        verify(chatPersistenceService).lockModel("u1", "s1", "deepseek-chat");
        // 缓存里必须是库里的值，否则后续请求会按错误的模型 409/放行
        verify(hashOperations).put("aichat:chat:sessionmodel:u1", "s1", "qwen2.5-coder:7b");
        verify(hashOperations, never()).put("aichat:chat:sessionmodel:u1", "s1", "deepseek-chat");
    }

    @Test
    void shouldBatchReadModelsFromRedisInOneCall() {
        when(hashOperations.entries("aichat:chat:sessionmodel:u1")).thenReturn(Map.of(
                "s1", "qwen2.5-coder:7b",
                "s2", "deepseek-chat"));

        Map<String, String> models = repository.getModels("u1", List.of("s1", "s2"));

        assertThat(models).containsEntry("s1", "qwen2.5-coder:7b")
                .containsEntry("s2", "deepseek-chat");
        // 全部命中缓存，不应回源 MySQL
        verify(chatPersistenceService, never()).getSessions(anyString());
        verify(hashOperations, never()).putAll(anyString(), any());
    }

    @Test
    void shouldFillMissingModelsWithSingleMysqlQueryAndRefillCache() {
        when(hashOperations.entries("aichat:chat:sessionmodel:u1")).thenReturn(Map.of(
                "s1", "qwen2.5-coder:7b"));
        // 未锁定的旧会话（model=null）不应被当成命中，也不应回填
        when(chatPersistenceService.getSessions("u1")).thenReturn(List.of(
                new ChatSession("u1", "s2", "标题2", "deepseek-chat",
                        LocalDateTime.of(2026, 10, 28, 10, 0),
                        LocalDateTime.of(2026, 10, 28, 10, 0)),
                new ChatSession("u1", "s3", "标题3", (String) null,
                        LocalDateTime.of(2026, 10, 28, 10, 0),
                        LocalDateTime.of(2026, 10, 28, 10, 0))));

        Map<String, String> models = repository.getModels("u1", List.of("s1", "s2", "s3"));

        assertThat(models).containsEntry("s1", "qwen2.5-coder:7b")
                .containsEntry("s2", "deepseek-chat")
                .doesNotContainKey("s3");
        // 3 个会话只回源 1 次
        verify(chatPersistenceService, times(1)).getSessions("u1");
        verify(hashOperations).putAll("aichat:chat:sessionmodel:u1", Map.of("s2", "deepseek-chat"));
    }
}
