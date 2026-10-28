package com.aichat.Service.impl;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aichat.Entity.ChatMessage;
import com.aichat.Entity.ChatSession;
import com.aichat.Mapper.ChatMessageMapper;
import com.aichat.Mapper.ChatSessionMapper;
import com.aichat.Service.ChatPersistenceService;

/**
 * 聊天数据持久化服务：MySQL 是聊天数据的持久化主存储，Redis 只作为缓存。
 */
@Service
public class ChatPersistenceServiceimpl implements ChatPersistenceService {

    @Autowired
    private ChatMessageMapper chatMessageMapper;
    @Autowired
    private ChatSessionMapper chatSessionMapper;

    // 消息和会话元数据在同一事务中写入，避免只成功一半。
    // 每条消息都 upsert 会话：首条创建元数据，后续刷新 update_time，保证会话列表按最近活跃排序。
    @Override
    @Transactional
    public ChatMessage saveMessage(ChatMessage message, String title) {
        chatMessageMapper.insert(message);
        ChatSession session = new ChatSession(
                message.getUserId(),
                message.getSessionId(),
                title,
                message.getCreateTime(),
                message.getCreateTime());
        chatSessionMapper.upsert(session);
        return message;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ChatMessage> getMessages(String userId, String sessionId) {
        return chatMessageMapper.selectBySession(userId, sessionId);
    }

    // Mapper 按 id 倒序取游标附近的一页，这里反转为升序，保证接口返回顺序稳定
    @Override
    @Transactional(readOnly = true)
    public List<ChatMessage> getMessages(String userId, String sessionId, Long beforeId, int limit) {
        List<ChatMessage> messages = beforeId == null
                ? chatMessageMapper.selectLatestBySession(userId, sessionId, limit)
                : chatMessageMapper.selectBySessionBeforeId(userId, sessionId, beforeId, limit);
        List<ChatMessage> ascending = new ArrayList<>(messages);
        Collections.reverse(ascending);
        return ascending;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ChatSession> getSessions(String userId) {
        return chatSessionMapper.selectByUser(userId);
    }

    @Override
    @Transactional(readOnly = true)
    public String getTitle(String userId, String sessionId) {
        return chatSessionMapper.selectTitle(userId, sessionId);
    }

    // 会话不存在时创建一条元数据，后续消息保存会复用该记录
    @Override
    @Transactional
    public void saveTitle(String userId, String sessionId, String title) {
        LocalDateTime now = LocalDateTime.now();
        chatSessionMapper.upsert(new ChatSession(userId, sessionId, title, null, now, now));
    }

    // 锁模型：upsert 时 model 走 COALESCE(model, #{model})，已锁定则不覆盖，未锁定则写入
    @Override
    @Transactional
    public void lockModel(String userId, String sessionId, String model) {
        LocalDateTime now = LocalDateTime.now();
        chatSessionMapper.upsert(new ChatSession(userId, sessionId, null, model, now, now));
    }

    @Override
    @Transactional(readOnly = true)
    public String getSessionModel(String userId, String sessionId) {
        return chatSessionMapper.selectModel(userId, sessionId);
    }

    // 先删消息再删会话，返回值用于区分“数据库有数据”和“只剩 Redis 缓存”
    @Override
    @Transactional
    public boolean clear(String userId, String sessionId) {
        int deletedMessages = chatMessageMapper.deleteBySession(userId, sessionId);
        int deletedSessions = chatSessionMapper.deleteBySession(userId, sessionId);
        return deletedMessages > 0 || deletedSessions > 0;
    }
}
