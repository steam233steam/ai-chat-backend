package com.aichat.Repository;

import java.util.List;
import java.util.Map;

import org.springframework.ai.chat.messages.Message;

import com.aichat.Dto.SMessage;

/**
 * 会话存储。
 * 所有 key 都带 userId 前缀，因此调用方只能访问自己的数据，无需再做额外的归属校验。
 */
public interface ChatSessionRepository {

    /**
     * 保存一条消息到 MySQL 主存储 + Redis 缓存。
     * @param model 本轮使用的模型名（来自 ModelRouter，会一并落库和写进缓存）
     */
    void save(String userId, String sessionid, Message message, String model);

    List<Message> getHistory(String userId, String sessionid);

    List<SMessage> getMessageHistory(String userId, String sessionid);

    /**
     * 游标分页：取 beforeId 之前（不含）的最近 limit 条消息，按 id 升序返回。
     * beforeId 为 null 时取最新一页。
     */
    List<SMessage> getMessageHistory(String userId, String sessionid, Long beforeId, int limit);

    boolean clear(String userId, String sessionid);

    List<String> getSessions(String userId);

    void saveTitle(String userId, String sessionid, String title);

    String getTitle(String userId, String sessionid);

    /**
     * 锁定会话模型（首条消息时调用）。已锁定的会话不会被覆盖。
     * 落 MySQL + 写 Redis 缓存。
     */
    void lockModel(String userId, String sessionid, String model);

    /**
     * 获取会话锁定的模型名。未锁定（新会话）返回 null。
     * 读 Redis 缓存，miss 时回源 MySQL。
     */
    String getModel(String userId, String sessionid);

    /**
     * 批量获取会话锁定的模型（sessionId → model），供会话列表使用，避免逐会话查库。
     * 一次 HGETALL 读缓存；未命中的会话用一次 MySQL 查询补齐并回填。
     * 未锁定模型的会话不会出现在返回的 Map 里。
     */
    Map<String, String> getModels(String userId, List<String> sessionIds);

}
