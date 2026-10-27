package com.aichat.Service;

import java.util.List;

import com.aichat.Entity.ChatMessage;
import com.aichat.Entity.ChatSession;

/**
 * 聊天数据持久化服务：MySQL 是聊天数据的持久化主存储，Redis 只作为缓存。
 */
public interface ChatPersistenceService {
    // 保存消息，返回带自增 id 的实体
    ChatMessage saveMessage(ChatMessage message, String title);

    // 获取消息
    List<ChatMessage> getMessages(String userId, String sessionId);

    // 游标分页获取消息（beforeId 为 null 时取最新一页），返回按 id 升序
    List<ChatMessage> getMessages(String userId, String sessionId, Long beforeId, int limit);

    // 获取会话
    List<ChatSession> getSessions(String userId);

    // 获取会话标题
    String getTitle(String userId, String sessionId);

    // 保存会话标题
    void saveTitle(String userId, String sessionId, String title);

    // 清空会话
    boolean clear(String userId, String sessionId);

}
