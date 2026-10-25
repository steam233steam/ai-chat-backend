package com.aichat.Service;

import java.util.List;
import java.util.Map;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import reactor.core.publisher.Flux;

public interface ChatService {

    String sendSession(String sessionid, UserMessage userMessage);

    boolean clearSession(String sessionid);

    List<Map<String, Object>> getHistory(String sessionid);

    List<Map<String, Object>> getSessions();

    void saveTitle(String sessionid, String title);

    Flux<String> sendSessionStream(String sessionid, String question);

}