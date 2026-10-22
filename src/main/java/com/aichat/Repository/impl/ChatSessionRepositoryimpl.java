package com.aichat.Repository.impl;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;

import com.aichat.Dto.SMessage;
import com.aichat.Entity.ChatMessage;
import com.aichat.Entity.ChatSession;
import com.aichat.Repository.ChatSessionRepository;
import com.aichat.Service.ChatPersistenceService;
import static com.aichat.utils.RedisConstants.*;

/**
 * 会话存储实现：MySQL 保存全量数据，Redis 作为 7 天热缓存。
 * 读缓存未命中时回源 MySQL 并回填；写操作先落库，再更新缓存。
 */
@Repository
public class ChatSessionRepositoryimpl implements ChatSessionRepository {
    private static final Logger log = LoggerFactory.getLogger(ChatSessionRepositoryimpl.class);

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;
    @Autowired
    private ChatPersistenceService chatPersistenceService;

    @Override
    public void save(String userId, String sessionid, Message message) {
        String type = getMessageType(message);
        long now = System.currentTimeMillis();
        SMessage sMessage = new SMessage(message.getText(), type, now);

        // 先写 MySQL，保证 Redis 过期或宕机后仍能恢复完整历史
        String title = getTitle(userId, sessionid);
        ChatMessage chatMessage = new ChatMessage(
                userId,
                sessionid,
                type,
                message.getText(),
                toLocalDateTime(now));
        chatPersistenceService.saveMessage(chatMessage, title);

        // 再更新 Redis；缓存失败只影响性能，不影响数据正确性
        try {
            String key = USER_SESSION_KEY_PREFIX + userId + ":" + sessionid;
            redisTemplate.opsForList().rightPush(key, sMessage);
            redisTemplate.expire(key, Duration.ofDays(SESSION_TTL_DAYS));

            String sessionsKey = USER_SESSIONS_KEY_PREFIX + userId;
            Long cachedSessionCount = redisTemplate.opsForZSet().size(sessionsKey);
            if (cachedSessionCount == null || cachedSessionCount == 0) {
                // 会话列表缓存已过期时从 MySQL 整体重建，避免只写入当前会话造成列表不完整
                cacheSessions(sessionsKey, chatPersistenceService.getSessions(userId));
            } else {
                redisTemplate.opsForZSet().add(sessionsKey, sessionid, now / 1000.0);
                redisTemplate.expire(sessionsKey, Duration.ofDays(SESSION_TTL_DAYS));
            }
        } catch (Exception e) {
            log.warn("写入 Redis 会话缓存失败，数据已落库: userId={}, sessionid={}", userId, sessionid, e);
            invalidateMessageCache(userId, sessionid);
            invalidateSessionsCache(userId);
        }
    }

    @Override
    public List<Message> getHistory(String userId, String sessionid) {
        return getMessageHistory(userId, sessionid).stream()
                .map(sMessage -> switch (sMessage.getType()) {
                    case "user" -> new UserMessage(sMessage.getText());
                    case "assistant" -> new AssistantMessage(sMessage.getText());
                    case "system" -> new SystemMessage(sMessage.getText());
                    default -> throw new IllegalArgumentException("未知消息类型: " + sMessage.getType());
                })
                .collect(Collectors.toList());
    }

    @Override
    public List<SMessage> getMessageHistory(String userId, String sessionid) {
        String key = USER_SESSION_KEY_PREFIX + userId + ":" + sessionid;
        List<SMessage> cached = readCachedMessages(key);
        if (!cached.isEmpty()) {
            return cached;
        }

        List<ChatMessage> messages = chatPersistenceService.getMessages(userId, sessionid);
        if (messages.isEmpty()) {
            return List.of();
        }

        List<SMessage> result = messages.stream()
                .map(message -> new SMessage(
                        message.getContent(),
                        message.getRole(),
                        toEpochMilli(message.getCreateTime())))
                .collect(Collectors.toList());
        cacheMessages(key, result);
        return result;
    }

    @Override
    public boolean clear(String userId, String sessionid) {
        boolean deleted = chatPersistenceService.clear(userId, sessionid);

        try {
            String sessionsKey = USER_SESSIONS_KEY_PREFIX + userId;
            String sessionKey = USER_SESSION_KEY_PREFIX + userId + ":" + sessionid;
            String titleKey = SESSION_TITLE_KEY_PREFIX + userId + ":" + sessionid;

            redisTemplate.opsForZSet().remove(sessionsKey, sessionid);
            redisTemplate.delete(titleKey);
            deleted = Boolean.TRUE.equals(redisTemplate.delete(sessionKey)) || deleted;
        } catch (Exception e) {
            log.warn("清理 Redis 会话缓存失败，数据库已清理: userId={}, sessionid={}", userId, sessionid, e);
        }

        return deleted;
    }

    @Override
    public List<String> getSessions(String userId) {
        String sessionsKey = USER_SESSIONS_KEY_PREFIX + userId;
        try {
            Set<Object> sessions = redisTemplate.opsForZSet().reverseRange(sessionsKey, 0, -1);
            if (sessions != null && !sessions.isEmpty()) {
                return sessions.stream()
                        .map(Object::toString)
                        .collect(Collectors.toList());
            }
        } catch (Exception e) {
            log.warn("读取 Redis 会话列表失败，回退 MySQL: userId={}", userId, e);
        }

        List<ChatSession> sessions = chatPersistenceService.getSessions(userId);
        if (sessions.isEmpty()) {
            return List.of();
        }

        cacheSessions(sessionsKey, sessions);
        return sessions.stream()
                .map(ChatSession::getSessionId)
                .collect(Collectors.toList());
    }

    @Override
    public void saveTitle(String userId, String sessionid, String title) {
        chatPersistenceService.saveTitle(userId, sessionid, title);

        try {
            String titleKey = SESSION_TITLE_KEY_PREFIX + userId + ":" + sessionid;
            redisTemplate.opsForHash().put(titleKey, sessionid, title);
            redisTemplate.expire(titleKey, Duration.ofDays(SESSION_TTL_DAYS));

            String sessionsKey = USER_SESSIONS_KEY_PREFIX + userId;
            Long cachedSessionCount = redisTemplate.opsForZSet().size(sessionsKey);
            if (cachedSessionCount == null || cachedSessionCount == 0) {
                cacheSessions(sessionsKey, chatPersistenceService.getSessions(userId));
            } else {
                redisTemplate.opsForZSet().add(sessionsKey, sessionid, System.currentTimeMillis() / 1000.0);
                redisTemplate.expire(sessionsKey, Duration.ofDays(SESSION_TTL_DAYS));
            }
        } catch (Exception e) {
            log.warn("写入 Redis 会话标题失败，标题已落库: userId={}, sessionid={}", userId, sessionid, e);
            invalidateTitleCache(userId, sessionid);
            invalidateSessionsCache(userId);
        }
    }

    @Override
    public String getTitle(String userId, String sessionid) {
        String titleKey = SESSION_TITLE_KEY_PREFIX + userId + ":" + sessionid;
        try {
            Object cached = redisTemplate.opsForHash().get(titleKey, sessionid);
            if (cached != null) {
                return cached.toString();
            }
        } catch (Exception e) {
            log.warn("读取 Redis 会话标题失败，回退 MySQL: userId={}, sessionid={}", userId, sessionid, e);
        }

        String title = chatPersistenceService.getTitle(userId, sessionid);
        if (title != null) {
            try {
                redisTemplate.opsForHash().put(titleKey, sessionid, title);
                redisTemplate.expire(titleKey, Duration.ofDays(SESSION_TTL_DAYS));
            } catch (Exception e) {
                log.warn("回填 Redis 会话标题失败: userId={}, sessionid={}", userId, sessionid, e);
            }
        }
        return title;
    }

    private List<SMessage> readCachedMessages(String key) {
        try {
            List<Object> messages = redisTemplate.opsForList().range(key, 0, -1);
            if (messages == null) {
                return List.of();
            }
            return messages.stream()
                    .filter(SMessage.class::isInstance)
                    .map(SMessage.class::cast)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("读取 Redis 消息缓存失败，回退 MySQL: key={}", key, e);
            return List.of();
        }
    }

    private void cacheMessages(String key, List<SMessage> messages) {
        try {
            redisTemplate.opsForList().rightPushAll(key, messages.toArray());
            redisTemplate.expire(key, Duration.ofDays(SESSION_TTL_DAYS));
        } catch (Exception e) {
            log.warn("回填 Redis 消息缓存失败: key={}", key, e);
            try {
                redisTemplate.delete(key);
            } catch (Exception deleteException) {
                log.warn("删除不完整的消息缓存失败: key={}", key, deleteException);
            }
        }
    }

    private void cacheSessions(String sessionsKey, List<ChatSession> sessions) {
        try {
            for (ChatSession session : sessions) {
                double score = session.getUpdateTime() == null
                        ? 0
                        : session.getUpdateTime().atZone(ZoneId.systemDefault()).toEpochSecond();
                redisTemplate.opsForZSet().add(sessionsKey, session.getSessionId(), score);
            }
            redisTemplate.expire(sessionsKey, Duration.ofDays(SESSION_TTL_DAYS));
        } catch (Exception e) {
            log.warn("回填 Redis 会话列表失败: sessionsKey={}", sessionsKey, e);
            try {
                redisTemplate.delete(sessionsKey);
            } catch (Exception deleteException) {
                log.warn("删除不完整的会话列表缓存失败: sessionsKey={}", sessionsKey, deleteException);
            }
        }
    }

    private void invalidateMessageCache(String userId, String sessionid) {
        try {
            redisTemplate.delete(USER_SESSION_KEY_PREFIX + userId + ":" + sessionid);
        } catch (Exception e) {
            log.warn("删除消息缓存失败: userId={}, sessionid={}", userId, sessionid, e);
        }
    }

    private void invalidateTitleCache(String userId, String sessionid) {
        try {
            redisTemplate.delete(SESSION_TITLE_KEY_PREFIX + userId + ":" + sessionid);
        } catch (Exception e) {
            log.warn("删除标题缓存失败: userId={}, sessionid={}", userId, sessionid, e);
        }
    }

    private void invalidateSessionsCache(String userId) {
        try {
            redisTemplate.delete(USER_SESSIONS_KEY_PREFIX + userId);
        } catch (Exception e) {
            log.warn("删除会话列表缓存失败: userId={}", userId, e);
        }
    }

    private String getMessageType(Message message) {
        if (message instanceof UserMessage)
            return "user";
        if (message instanceof AssistantMessage)
            return "assistant";
        if (message instanceof SystemMessage)
            return "system";
        return "unknown";
    }

    private LocalDateTime toLocalDateTime(long epochMilli) {
        return LocalDateTime.ofInstant(
                Instant.ofEpochMilli(epochMilli),
                ZoneId.systemDefault());
    }

    private long toEpochMilli(LocalDateTime time) {
        return time == null
                ? 0
                : time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
}
