package com.aichat.Service.impl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.aichat.Dto.SMessage;
import com.aichat.Dto.UserDto;
import com.aichat.Exception.BusinessException;
import com.aichat.Repository.ChatSessionRepository;
import com.aichat.Service.ChatService;
import com.aichat.utils.UserHolder;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import reactor.core.scheduler.Schedulers;

@Service
public class ChatServiceimpl implements ChatService {
    private static final Logger log = LoggerFactory.getLogger(ChatServiceimpl.class);

    /** 会话标题最大长度（按 Unicode 字符计） */
    private static final int TITLE_MAX_LENGTH = 20;

    @Autowired
    private ChatSessionRepository chatSessionRepository;
    @Autowired
    private ChatClient chatClient;

    @Override
    public String SendSession(String sessionid, UserMessage userMessage) {
        String userId = currentUserId();
        String question = userMessage.getText();

        // 先读历史、再落库：当前问题只通过下面的 .user(question) 进入上下文，避免重复发送
        List<Message> history = chatSessionRepository.getHistory(userId, sessionid);
        chatSessionRepository.save(userId, sessionid, userMessage);
        if (history.isEmpty()) {
            chatSessionRepository.saveTitle(userId, sessionid, buildTitle(question));
        }

        try {
            String response = chatClient.prompt()
                    .messages(history)
                    .user(question)
                    .call()
                    .content();
            chatSessionRepository.save(userId, sessionid, new AssistantMessage(response));
            return response;
        } catch (Exception e) {
            log.error("AI 调用失败: userId={}, sessionid={}", userId, sessionid, e);
            throw new BusinessException(500, "AI 服务暂时不可用，请稍后重试");
        }
    }

    @Override
    public boolean ClearSession(String sessionid) {
        String userId = currentUserId();
        return chatSessionRepository.clear(userId, sessionid);
    }

    @Override
    public List<Map<String, Object>> getHistory(String sessionid) {
        String userId = currentUserId();
        List<SMessage> messages = chatSessionRepository.getMessageHistory(userId, sessionid);
        return messages.stream().map(msg -> {
            Map<String, Object> m = new HashMap<>();
            m.put("role", msg.getType());
            m.put("content", msg.getText());
            m.put("timestamp", msg.getTimestamp());
            return m;
        }).toList();
    }

    @Override
    public List<Map<String, Object>> getSessions() {
        String userId = currentUserId();
        List<String> sessionIds = chatSessionRepository.getSessions(userId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (String sessionId : sessionIds) {
            Map<String, Object> session = new HashMap<>();
            session.put("sessionId", sessionId);
            session.put("title", chatSessionRepository.getTitle(userId, sessionId));
            result.add(session);
        }
        return result;
    }

    @Override
    public void saveTitle(String sessionid, String title) {
        String userId = currentUserId();
        chatSessionRepository.saveTitle(userId, sessionid, title);
    }

    @Override
    public Flux<String> sendSessionStream(String sessionid, String question) {
        String userId = currentUserId();

        // 与同步接口保持一致：历史先读出来，再把当前问题落库，避免问题重复进入上下文
        List<Message> history = chatSessionRepository.getHistory(userId, sessionid);
        chatSessionRepository.save(userId, sessionid, new UserMessage(question));
        if (history.isEmpty()) {
            chatSessionRepository.saveTitle(userId, sessionid, buildTitle(question));
        }

        StringBuilder fullResponse = new StringBuilder();

        return chatClient.prompt()
                .messages(history)
                .user(question)
                .stream()
                .content()
                .doOnNext(fullResponse::append)
                .doOnError(e -> log.error("流式 AI 调用失败: userId={}, sessionid={}", userId, sessionid, e))
                .onErrorResume(e -> Flux.just("AI 服务暂时不可用，请稍后重试"))
                .doFinally(signal -> {
                    // 正常结束或客户端主动断开时都保存已生成的部分，避免上下文丢失
                    if (fullResponse.length() > 0
                            && (signal == SignalType.ON_COMPLETE || signal == SignalType.CANCEL)) {
                        saveAssistantReply(userId, sessionid, fullResponse.toString());
                    }
                });
    }

    private void saveAssistantReply(String userId, String sessionid, String content) {
        Mono.fromRunnable(() -> chatSessionRepository.save(userId, sessionid, new AssistantMessage(content)))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(v -> {
                }, e -> log.error("保存助手回复失败: userId={}, sessionid={}", userId, sessionid, e));
    }

    private String buildTitle(String question) {
        if (question == null || question.isBlank()) {
            return "新会话";
        }
        String trimmed = question.strip();
        if (trimmed.codePointCount(0, trimmed.length()) <= TITLE_MAX_LENGTH) {
            return trimmed;
        }
        return trimmed.substring(0, trimmed.offsetByCodePoints(0, TITLE_MAX_LENGTH)) + "...";
    }

    private String currentUserId() {
        UserDto user = UserHolder.getUser();
        if (user == null || user.getId() == null) {
            throw new BusinessException(401, "请先登录");
        }
        return user.getId();
    }
}
