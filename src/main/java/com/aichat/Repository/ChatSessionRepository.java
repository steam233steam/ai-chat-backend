package com.aichat.Repository;

import java.util.List;

import org.springframework.ai.chat.messages.Message;

import com.aichat.Dto.SMessage;

/**
 * 会话存储。
 * 所有 key 都带 userId 前缀，因此调用方只能访问自己的数据，无需再做额外的归属校验。
 */
public interface ChatSessionRepository {

    void save(String userId, String sessionid, Message message);

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

}
