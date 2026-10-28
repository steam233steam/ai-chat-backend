package com.aichat.Repository.impl;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    public void save(String userId, String sessionid, Message message, String model) {
        String type = getMessageType(message);
        long now = System.currentTimeMillis();

        // 先写 MySQL，保证 Redis 过期或宕机后仍能恢复完整历史
        String title = getTitle(userId, sessionid);
        ChatMessage chatMessage = new ChatMessage(
                userId,
                sessionid,
                type,
                message.getText(),
                model,
                toLocalDateTime(now));
        ChatMessage saved = chatPersistenceService.saveMessage(chatMessage, title);

        // MySQL 回填的自增 id + model 一并写入缓存，作为分页游标与模型追溯依据
        SMessage sMessage = new SMessage(saved.getId(), message.getText(), type, model, now);

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
                .map(this::toSMessage)
                .collect(Collectors.toList());
        cacheMessages(key, result);
        return result;
    }

    @Override
    public List<SMessage> getMessageHistory(String userId, String sessionid, Long beforeId, int limit) {
        String key = USER_SESSION_KEY_PREFIX + userId + ":" + sessionid;
        List<SMessage> cached = readCachedMessages(key);
        if (!cached.isEmpty()) {
            // 缓存保存的是全量历史，直接按 id 游标过滤后取最近 limit 条
            List<SMessage> filtered = cached.stream()
                    .filter(m -> beforeId == null || m.getId() < beforeId)
                    .collect(Collectors.toList());
            if (filtered.size() <= limit) {
                return filtered;
            }
            return new ArrayList<>(filtered.subList(filtered.size() - limit, filtered.size()));
        }

        // 缓存未命中时不回填，避免把“一页”当成全量历史写进缓存
        return chatPersistenceService.getMessages(userId, sessionid, beforeId, limit).stream()
                .map(this::toSMessage)
                .collect(Collectors.toList());
    }

    @Override
    public boolean clear(String userId, String sessionid) {
        boolean deleted = chatPersistenceService.clear(userId, sessionid);

        try {
            String sessionsKey = USER_SESSIONS_KEY_PREFIX + userId;
            String sessionKey = USER_SESSION_KEY_PREFIX + userId + ":" + sessionid;

            redisTemplate.opsForZSet().remove(sessionsKey, sessionid);
            redisTemplate.opsForHash().delete(SESSION_TITLE_KEY_PREFIX + userId, sessionid);
            redisTemplate.opsForHash().delete(SESSION_MODEL_KEY_PREFIX + userId, sessionid);
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
            // 与 model 缓存结构一致：hash key 只到 userId，field 用 sessionid
            String titleKey = SESSION_TITLE_KEY_PREFIX + userId;
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
        // 与 saveTitle 一致：hash key 只到 userId，field 用 sessionid
        String titleKey = SESSION_TITLE_KEY_PREFIX + userId;
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

    @Override
    public void lockModel(String userId, String sessionid, String model) {
        chatPersistenceService.lockModel(userId, sessionid, model);

        // 以 MySQL 为准回填缓存：并发首条消息时 upsert 的 COALESCE 只会保留先到的模型，
        // 直接写请求模型会让 Redis 与库不一致（后续请求会按错误的模型 409 或放行）。
        String effective = chatPersistenceService.getSessionModel(userId, sessionid);
        if (effective == null) {
            effective = model;
        }

        try {
            String modelKey = SESSION_MODEL_KEY_PREFIX + userId;
            redisTemplate.opsForHash().put(modelKey, sessionid, effective);
            redisTemplate.expire(modelKey, Duration.ofDays(SESSION_TTL_DAYS));
        } catch (Exception e) {
            log.warn("写入 Redis 会话模型缓存失败，模型已落库: userId={}, sessionid={}", userId, sessionid, e);
            redisTemplate.delete(SESSION_MODEL_KEY_PREFIX + userId);
        }
    }

    @Override
    public String getModel(String userId, String sessionid) {
        String modelKey = SESSION_MODEL_KEY_PREFIX + userId;
        try {
            Object cached = redisTemplate.opsForHash().get(modelKey, sessionid);
            if (cached != null) {
                return cached.toString();
            }
        } catch (Exception e) {
            log.warn("读取 Redis 会话模型缓存失败，回退 MySQL: userId={}, sessionid={}", userId, sessionid, e);
        }

        String model = chatPersistenceService.getSessionModel(userId, sessionid);
        if (model != null) {
            try {
                redisTemplate.opsForHash().put(modelKey, sessionid, model);
                redisTemplate.expire(modelKey, Duration.ofDays(SESSION_TTL_DAYS));
            } catch (Exception e) {
                log.warn("回填 Redis 会话模型缓存失败: userId={}, sessionid={}", userId, sessionid, e);
            }
        }
        return model;
    }

    @Override
    public Map<String, String> getModels(String userId, List<String> sessionIds) {
        if (sessionIds == null || sessionIds.isEmpty()) {
            return Map.of();
        }
        String modelKey = SESSION_MODEL_KEY_PREFIX + userId;
        Map<String, String> models = new HashMap<>();

        // 一次 HGETALL 拿到该用户全部已锁定的会话模型，再按需要的 sessionId 取子集
        try {
            Map<Object, Object> cached = redisTemplate.opsForHash().entries(modelKey);
            if (cached != null) {
                for (String sessionId : sessionIds) {
                    Object model = cached.get(sessionId);
                    if (model != null) {
                        models.put(sessionId, model.toString());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("批量读取 Redis 会话模型缓存失败，回退 MySQL: userId={}", userId, e);
        }

        Set<String> missing = sessionIds.stream()
                .filter(sessionId -> !models.containsKey(sessionId))
                .collect(Collectors.toSet());
        if (missing.isEmpty()) {
            return models;
        }

        // 未命中的会话用一次 MySQL 查询补齐；model 为 null 的旧会话不回填（Redis Hash 存不了 null）
        Map<String, String> refill = new HashMap<>();
        for (ChatSession session : chatPersistenceService.getSessions(userId)) {
            if (session.getModel() == null || !missing.contains(session.getSessionId())) {
                continue;
            }
            models.put(session.getSessionId(), session.getModel());
            refill.put(session.getSessionId(), session.getModel());
        }

        if (!refill.isEmpty()) {
            try {
                redisTemplate.opsForHash().putAll(modelKey, refill);
                redisTemplate.expire(modelKey, Duration.ofDays(SESSION_TTL_DAYS));
            } catch (Exception e) {
                log.warn("回填 Redis 会话模型缓存失败: userId={}", userId, e);
            }
        }
        return models;
    }

    private List<SMessage> readCachedMessages(String key) {
        try {
            List<Object> messages = redisTemplate.opsForList().range(key, 0, -1);
            if (messages == null || messages.isEmpty()) {
                return List.of();
            }
            List<SMessage> result = messages.stream()
                    .filter(SMessage.class::isInstance)
                    .map(SMessage.class::cast)
                    .collect(Collectors.toList());
            // 旧版本缓存缺少 id 时无法做游标分页，视为未命中并回源重建。
            // model 为 null 是历史消息的正常状态（加 model 列之前的数据），不能作为缓存失效条件，
            // 否则这些会话每次读取都会回源 MySQL，永远无法命中缓存。
            if (result.stream().anyMatch(m -> m.getId() == null)) {
                log.info("消息缓存缺少 id，回源 MySQL 重建: key={}", key);
                return List.of();
            }
            // 并发请求下 Redis 的写入顺序可能和 MySQL id 顺序不一致，读时按 id 校正
            result.sort(Comparator.comparing(SMessage::getId));
            return result;
        } catch (Exception e) {
            log.warn("读取 Redis 消息缓存失败，回退 MySQL: key={}", key, e);
            return List.of();
        }
    }

    private void cacheMessages(String key, List<SMessage> messages) {
        try {
            // 覆盖前先删，避免旧格式缓存或上次回填残留导致消息重复
            redisTemplate.delete(key);
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
            // 与 saveTitle/getTitle 的 hash key 保持一致：只到 userId，删的是 field
            redisTemplate.opsForHash().delete(SESSION_TITLE_KEY_PREFIX + userId, sessionid);
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

    private SMessage toSMessage(ChatMessage message) {
        return new SMessage(
                message.getId(),
                message.getContent(),
                message.getRole(),
                message.getModel(),
                toEpochMilli(message.getCreateTime()));
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
