package com.aichat.Service.impl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.aichat.Config.ModelRouter;
import com.aichat.Dto.SMessage;
import com.aichat.Dto.UserDto;
import com.aichat.Exception.BusinessException;
import com.aichat.Repository.ChatSessionRepository;
import com.aichat.Service.ChatService;
import com.aichat.Service.ChatTitleService;
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

    /** 送给模型的历史消息上限；<=0 表示不裁剪 */
    @Value("${app.chat.context-max-messages:20}")
    private int contextMaxMessages = 20;

    @Autowired
    private ChatSessionRepository chatSessionRepository;
    @Autowired
    private ModelRouter modelRouter;
    @Autowired
    private ChatTitleService chatTitleService;

    @Override
    public String sendSession(String sessionid, UserMessage userMessage, String modelName) {
        String userId = currentUserId();
        String question = userMessage.getText();
        ChatClient chatClient = modelRouter.client(modelName);

        // 先读历史、再落库：当前问题只通过下面的 .user(question) 进入上下文，避免重复发送
        List<Message> history = trimHistory(chatSessionRepository.getHistory(userId, sessionid));
        initSessionIfNeeded(userId, sessionid, question, history);
        chatSessionRepository.save(userId, sessionid, userMessage);

        try {
            String response = chatClient.prompt()
                    .messages(history)
                    .user(question)
                    .call()
                    .content();
            chatSessionRepository.save(userId, sessionid, new AssistantMessage(response));
            return response;
        } catch (Exception e) {
            log.error("AI 调用失败: userId={}, sessionid={}, model={}", userId, sessionid, modelName, e);
            throw new BusinessException(500, "AI 服务暂时不可用，请稍后重试");
        }
    }

    @Override
    public boolean clearSession(String sessionid) {
        String userId = currentUserId();
        return chatSessionRepository.clear(userId, sessionid);
    }

    @Override
    public List<Map<String, Object>> getHistory(String sessionid) {
        return getHistory(sessionid, null, 0);
    }

    @Override
    public List<Map<String, Object>> getHistory(String sessionid, Long beforeId, int limit) {
        String userId = currentUserId();
        List<SMessage> messages = limit > 0
                ? chatSessionRepository.getMessageHistory(userId, sessionid, beforeId, limit)
                : chatSessionRepository.getMessageHistory(userId, sessionid);
        return messages.stream().map(msg -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", msg.getId());
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
    public Flux<String> sendSessionStream(String sessionid, String question, String modelName) {
        String userId = currentUserId();
        ChatClient chatClient = modelRouter.client(modelName);

        // 与同步接口保持一致：历史先读出来，再把当前问题落库，避免问题重复进入上下文
        List<Message> history = trimHistory(chatSessionRepository.getHistory(userId, sessionid));
        initSessionIfNeeded(userId, sessionid, question, history);
        chatSessionRepository.save(userId, sessionid, new UserMessage(question));

        StringBuilder fullResponse = new StringBuilder();

        return chatClient.prompt()
                .messages(history)
                .user(question)
                .stream()
                .content()
                .doOnNext(fullResponse::append)
                .doOnError(e -> log.error("流式 AI 调用失败: userId={}, sessionid={}, model={}", userId, sessionid, modelName, e))
                .onErrorResume(e -> Flux.just("AI 服务暂时不可用，请稍后重试"))
                .doFinally(signal -> {
                    // 正常结束或客户端主动断开时都保存已生成的部分，避免上下文丢失
                    if (fullResponse.length() > 0
                            && (signal == SignalType.ON_COMPLETE || signal == SignalType.CANCEL)) {
                        saveAssistantReply(userId, sessionid, fullResponse.toString());
                    }
                });
    }

    @Override
    public Set<String> availableModels() {
        return modelRouter.availableModels();
    }

    private void saveAssistantReply(String userId, String sessionid, String content) {
        Mono.fromRunnable(() -> chatSessionRepository.save(userId, sessionid, new AssistantMessage(content)))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(v -> {
                }, e -> log.error("保存助手回复失败: userId={}, sessionid={}", userId, sessionid, e));
    }

    /**
     * 只保留最近 N 条历史送给模型，控制 Prompt 长度。
     * 历史接口仍然返回全量/分页数据，裁剪只影响 AI 上下文。
     */
    private List<Message> trimHistory(List<Message> history) {
        if (contextMaxMessages <= 0 || history.size() <= contextMaxMessages) {
            return history;
        }
        return new ArrayList<>(history.subList(history.size() - contextMaxMessages, history.size()));
    }

    /**
     * 首次对话时先写兜底标题并落库，再提交异步 AI 标题任务。
     * 先落库是为了避免会话元数据出现 title=null 的中间状态。
     */
    private void initSessionIfNeeded(String userId, String sessionId, String question, List<Message> history) {
        if (!history.isEmpty()) {
            return;
        }
        String fallbackTitle = buildTitle(question);
        chatSessionRepository.saveTitle(userId, sessionId, fallbackTitle);
        chatTitleService.generateTitleAsync(userId, sessionId, question, fallbackTitle);
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
