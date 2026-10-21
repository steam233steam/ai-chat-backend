package com.aichat.Service;

import java.util.List;
import java.util.Map;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import reactor.core.publisher.Flux;

public interface ChatService {

    public String SendSession(String sessionid, UserMessage userMessage);

    public boolean ClearSession(String sessionid);

    public List<Map<String, Object>> getHistory(String sessionid);

    List<Map<String, Object>> getSessions();

    public void saveTitle(String sessionid, String title);

    public Flux<String> sendSessionStream(String sessionid, String question);

}