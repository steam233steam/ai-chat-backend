package com.aichat.Service.impl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

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
import com.aichat.utils.ChatHistoryBuilder;
import com.aichat.utils.ModelOutputSanitizer;
import com.aichat.utils.StreamingSafetyFilter;
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

    /** 送给模型的历史消息条数上限；<=0 表示不裁剪 */
    @Value("${app.chat.context-max-messages:20}")
    private int contextMaxMessages = 20;

    /** 送给模型的历史总字符预算；<=0 表示不限制 */
    @Value("${app.chat.context-max-chars:24000}")
    private int contextMaxChars = 24000;

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
        // 先解析出本轮真正使用的模型：前端不传时也要把默认模型写进消息，而不是记 null
        String model = modelRouter.resolve(modelName);

        // 会话锁模型：新会话锁到本次传入的模型；已有会话若传入模型与锁定模型不一致 → 409
        // 放在取 client 之前：冲突时不必白取一次 ChatClient
        enforceModelLock(userId, sessionid, model);

        ChatClient chatClient = modelRouter.client(model);

        // 先读历史、再落库：当前问题只通过最后一条 UserMessage 进入上下文，避免重复发送。
        // 历史必须保持真实角色（User/Assistant），不能拼进 system prompt。
        List<Message> historyMessages = buildHistoryMessages(userId, sessionid);
        initSessionIfNeeded(userId, sessionid, question, !historyMessages.isEmpty());
        chatSessionRepository.save(userId, sessionid, userMessage, model);

        try {
            String rawResponse = withHistory(chatClient, historyMessages, question)
                    .call()
                    .content();
            // 输出净化：剥掉模型偶尔泄漏的内部 <ds_safety> 审核标签，避免脏数据落库/进前端
            String response = ModelOutputSanitizer.sanitize(rawResponse);
            chatSessionRepository.save(userId, sessionid, new AssistantMessage(response), model);
            return response;
        } catch (Exception e) {
            log.error("AI 调用失败: userId={}, sessionid={}, model={}", userId, sessionid, model, e);
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
            m.put("model", msg.getModel());
            m.put("timestamp", msg.getTimestamp());
            return m;
        }).toList();
    }

    @Override
    public List<Map<String, Object>> getSessions() {
        String userId = currentUserId();
        List<String> sessionIds = chatSessionRepository.getSessions(userId);
        // 批量取模型：一次 HGETALL + 最多一次 MySQL，避免按会话逐个查库（标题有缓存摊薄，保持原逻辑）
        Map<String, String> models = chatSessionRepository.getModels(userId, sessionIds);
        List<Map<String, Object>> result = new ArrayList<>();
        for (String sessionId : sessionIds) {
            Map<String, Object> session = new HashMap<>();
            session.put("sessionId", sessionId);
            session.put("title", chatSessionRepository.getTitle(userId, sessionId));
            session.put("model", models.get(sessionId));
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
        // 与同步接口一致：先解析默认模型，落库和日志都记录实际使用的模型
        String model = modelRouter.resolve(modelName);

        // 会话锁模型：与同步接口一致，放在取 client 之前，冲突时不必白取
        enforceModelLock(userId, sessionid, model);

        ChatClient chatClient = modelRouter.client(model);

        // 与同步接口一致：历史保持真实角色，当前问题作为最后一条 UserMessage
        List<Message> historyMessages = buildHistoryMessages(userId, sessionid);
        initSessionIfNeeded(userId, sessionid, question, !historyMessages.isEmpty());
        chatSessionRepository.save(userId, sessionid, new UserMessage(question), model);

        StringBuilder fullResponse = new StringBuilder();
        AtomicBoolean failed = new AtomicBoolean(false);
        StreamingSafetyFilter safetyFilter = new StreamingSafetyFilter();

        return withHistory(chatClient, historyMessages, question)
                .stream()
                .content()
                .map(safetyFilter::accept)
                .filter(chunk -> !chunk.isEmpty())
                .concatWith(Flux.defer(() -> {
                    String tail = safetyFilter.finish();
                    return tail.isEmpty() ? Flux.empty() : Flux.just(tail);
                }))
                .doOnNext(fullResponse::append)
                .doOnError(e -> {
                    failed.set(true);
                    log.error("流式 AI 调用失败: userId={}, sessionid={}, model={}", userId, sessionid, model, e);
                })
                .onErrorResume(e -> Flux.just("AI 服务暂时不可用，请稍后重试"))
                .doFinally(signal -> {
                    // 正常结束或客户端主动断开才保存；失败兜底文案不写入历史，避免污染下一轮上下文
                    if (!failed.get() && fullResponse.length() > 0
                            && (signal == SignalType.ON_COMPLETE || signal == SignalType.CANCEL)) {
                        saveAssistantReply(userId, sessionid, fullResponse.toString(), model);
                    }
                });
    }

    @Override
    public Set<String> availableModels() {
        return modelRouter.availableModels();
    }

    private void saveAssistantReply(String userId, String sessionid, String content, String model) {
        // 输出净化：剥掉流式回复里可能泄漏的 <ds_safety> 内部标签，避免脏数据落库
        String clean = ModelOutputSanitizer.sanitize(content);
        Mono.fromRunnable(() -> chatSessionRepository.save(userId, sessionid, new AssistantMessage(clean), model))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(v -> {
                }, e -> log.error("保存助手回复失败: userId={}, sessionid={}, model={}", userId, sessionid, model, e));
    }

    /**
     * 会话锁模型（学 Cursor 的工业做法）：
     * - 新会话（无锁定模型）：把本次解析出的 model 锁进会话
     * - 已有会话且传入模型 == 锁定模型：放行
     * - 已有会话且传入模型 != 锁定模型：409，提示用户"要换模型请新建会话"
     * 目的：避免同一会话反复切模型导致"分布外上下文"污染，弱模型身份漂移。
     */
    private void enforceModelLock(String userId, String sessionid, String model) {
        String lockedModel = chatSessionRepository.getModel(userId, sessionid);
        if (lockedModel == null) {
            chatSessionRepository.lockModel(userId, sessionid, model);
            log.info("会话锁定模型: userId={}, sessionid={}, model={}", userId, sessionid, model);
        } else if (!lockedModel.equals(model)) {
            throw new BusinessException(409,
                    "该会话已锁定为 " + lockedModel + "，切换模型请新建会话");
        }
    }

    /**
     * 读取该会话历史，转换成真实角色消息，并按条数 + 字符预算裁剪。
     * 安全边界：历史只作为 UserMessage / AssistantMessage 传入，绝不拼进 system prompt。
     */
    private List<Message> buildHistoryMessages(String userId, String sessionid) {
        List<SMessage> history = chatSessionRepository.getMessageHistory(userId, sessionid);
        return ChatHistoryBuilder.build(history, contextMaxMessages, contextMaxChars);
    }

    /**
     * 组装本轮 Prompt：system 只放可信人设，历史是真实对话角色，当前问题最后追加。
     */
    private ChatClient.ChatClientRequestSpec withHistory(
            ChatClient chatClient, List<Message> historyMessages, String question) {
        List<Message> messages = new ArrayList<>(historyMessages.size() + 1);
        messages.addAll(historyMessages);
        messages.add(new UserMessage(question));
        return chatClient.prompt()
                .system(ModelRouter.SYSTEM_PROMPT)
                .messages(messages);
    }

    /**
     * 首次对话时先写兜底标题并落库，再提交异步 AI 标题任务。
     * 先落库是为了避免会话元数据出现 title=null 的中间状态。
     */
    private void initSessionIfNeeded(String userId, String sessionId, String question, boolean hasHistory) {
        if (hasHistory) {
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
        String trimmed = question.strip().replaceAll("[\\r\\n\\t]+", " ");
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
