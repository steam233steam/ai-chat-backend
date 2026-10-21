package com.aichat.Repository.impl;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;

import com.aichat.Dto.SMessage;
import com.aichat.Repository.ChatSessionRepository;
import static com.aichat.utils.RedisConstants.*;

@Repository
public class ChatSessionRepositoryimpl implements ChatSessionRepository {
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Override
    public void save(String userId, String sessionid, Message message) {
        String type = getMessageType(message);
        SMessage sMessage = new SMessage(message.getText(), type, System.currentTimeMillis());
        String key = USER_SESSION_KEY_PREFIX + userId + ":" + sessionid;
        redisTemplate.opsForList().rightPush(key, sMessage);
        redisTemplate.expire(key, Duration.ofDays(SESSION_TTL_DAYS));
        String sessionsKey = USER_SESSIONS_KEY_PREFIX + userId;
        redisTemplate.opsForZSet().add(sessionsKey, sessionid, System.currentTimeMillis() / 1000.0);
        redisTemplate.expire(sessionsKey, Duration.ofDays(SESSION_TTL_DAYS));
    }

    @Override
    public List<Message> getHistory(String userId, String sessionid) {
        String key = USER_SESSION_KEY_PREFIX + userId + ":" + sessionid;
        List<Object> messages = redisTemplate.opsForList().range(key, 0, -1);

        if (messages == null || messages.isEmpty()) {
            return List.of();
        }

        return messages.stream()
                .filter(obj -> obj instanceof SMessage)
                .map(obj -> (SMessage) obj)
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
        List<Object> messages = redisTemplate.opsForList().range(key, 0, -1);

        if (messages == null || messages.isEmpty()) {
            return List.of();
        }

        return messages.stream()
                .filter(obj -> obj instanceof SMessage)
                .map(obj -> (SMessage) obj)
                .collect(Collectors.toList());
    }

    @Override
    public boolean clear(String userId, String sessionid) {
        String sessionsKey = USER_SESSIONS_KEY_PREFIX + userId;
        String sessionKey = USER_SESSION_KEY_PREFIX + userId + ":" + sessionid;
        String titleKey = SESSION_TITLE_KEY_PREFIX + userId + ":" + sessionid;

        redisTemplate.opsForZSet().remove(sessionsKey, sessionid);
        redisTemplate.delete(titleKey);
        return redisTemplate.delete(sessionKey);
    }

    @Override
    public List<String> getSessions(String userId) {
        String sessionsKey = USER_SESSIONS_KEY_PREFIX + userId;
        Set<Object> sessions = redisTemplate.opsForZSet().reverseRange(sessionsKey, 0, -1);
        if (sessions == null) {
            return List.of();
        }
        return sessions.stream()
                .map(Object::toString)
                .collect(Collectors.toList());
    }

    @Override
    public void saveTitle(String userId, String sessionid, String title) {
        String titleKey = SESSION_TITLE_KEY_PREFIX + userId + ":" + sessionid;
        redisTemplate.opsForHash().put(titleKey, sessionid, title);
        redisTemplate.expire(titleKey, Duration.ofDays(SESSION_TTL_DAYS));
    }

    @Override
    public String getTitle(String userId, String sessionid) {
        String titleKey = SESSION_TITLE_KEY_PREFIX + userId + ":" + sessionid;
        return (String) redisTemplate.opsForHash().get(titleKey, sessionid);
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

}
