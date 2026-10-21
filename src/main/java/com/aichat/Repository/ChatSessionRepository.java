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

    boolean clear(String userId, String sessionid);

    List<String> getSessions(String userId);

    void saveTitle(String userId, String sessionid, String title);

    String getTitle(String userId, String sessionid);

}
