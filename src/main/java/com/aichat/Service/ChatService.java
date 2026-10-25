package com.aichat.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.ai.chat.messages.UserMessage;

import reactor.core.publisher.Flux;

public interface ChatService {

    /** 同步对话。modelName 为 null/空 时用默认模型；不在白名单抛 400。 */
    String sendSession(String sessionid, UserMessage userMessage, String modelName);

    boolean clearSession(String sessionid);

    List<Map<String, Object>> getHistory(String sessionid);

    List<Map<String, Object>> getSessions();

    void saveTitle(String sessionid, String title);

    /** 流式对话。modelName 语义同 sendSession。 */
    Flux<String> sendSessionStream(String sessionid, String question, String modelName);

    /** 可用模型白名单（前端渲染下拉框用） */
    Set<String> availableModels();

}
