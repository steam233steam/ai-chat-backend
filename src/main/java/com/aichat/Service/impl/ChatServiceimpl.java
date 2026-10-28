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
import com.aichat.utils.ChatHistoryFormatter;
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
        // 先解析出本轮真正使用的模型：前端不传时也要把默认模型写进消息，而不是记 null
        String model = modelRouter.resolve(modelName);

        // 会话锁模型：新会话锁到本次传入的模型；已有会话若传入模型与锁定模型不一致 → 409
        // 放在取 client 之前：冲突时不必白取一次 ChatClient
        enforceModelLock(userId, sessionid, model);

        ChatClient chatClient = modelRouter.client(model);

        // 先读历史、再落库：当前问题只通过下面的 .user(question) 进入上下文，避免重复发送。
        // 历史用带发言者标注的文本喂入，让模型区分"自己说的"和"别的模型说的"
        String historyBlock = buildLabeledHistory(userId, sessionid, model);
        initSessionIfNeeded(userId, sessionid, question, historyBlock);
        chatSessionRepository.save(userId, sessionid, userMessage, model);

        try {
            String response = withHistory(chatClient, historyBlock)
                    .user(question)
                    .call()
                    .content();
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

        // 与同步接口保持一致：历史先读出来（带发言者标注），再把当前问题落库，避免问题重复进入上下文
        String historyBlock = buildLabeledHistory(userId, sessionid, model);
        initSessionIfNeeded(userId, sessionid, question, historyBlock);
        chatSessionRepository.save(userId, sessionid, new UserMessage(question), model);

        StringBuilder fullResponse = new StringBuilder();

        return withHistory(chatClient, historyBlock)
                .user(question)
                .stream()
                .content()
                .doOnNext(fullResponse::append)
                .doOnError(e -> log.error("流式 AI 调用失败: userId={}, sessionid={}, model={}", userId, sessionid, model, e))
                .onErrorResume(e -> Flux.just("AI 服务暂时不可用，请稍后重试"))
                .doFinally(signal -> {
                    // 正常结束或客户端主动断开时都保存已生成的部分，避免上下文丢失
                    if (fullResponse.length() > 0
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
        Mono.fromRunnable(() -> chatSessionRepository.save(userId, sessionid, new AssistantMessage(content), model))
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
     * 读取该会话最近 N 条历史，渲染成带发言者标注的文本块，作为模型上下文。
     * 每条历史标注"发言者: 模型名（我/其他模型）"，让当前模型区分"自己说的"和"别的模型说的"。
     * 历史为空时返回空串（表示无上下文）。
     */
    private String buildLabeledHistory(String userId, String sessionid, String currentModel) {
        List<SMessage> history = chatSessionRepository.getMessageHistory(userId, sessionid);
        if (contextMaxMessages <= 0 || history.size() <= contextMaxMessages) {
            return ChatHistoryFormatter.format(history, currentModel);
        }
        // 只保留最近 N 条，控制 Prompt 长度
        List<SMessage> trimmed = history.subList(history.size() - contextMaxMessages, history.size());
        return ChatHistoryFormatter.format(trimmed, currentModel);
    }

    /**
     * 把"带发言者标注的历史文本块"挂到 prompt 上；历史为空则只返回 prompt（不带历史）。
     * 历史作为一条 system message 注入，模型据此理解跨模型会话背景。
     * 措辞做防呆：明确告诉弱模型"其他模型的发言不是你说的，只做背景参考"。
     */
    private ChatClient.ChatClientRequestSpec withHistory(ChatClient chatClient, String historyBlock) {
        if (historyBlock == null || historyBlock.isBlank()) {
            return chatClient.prompt();
        }
        String historyPrompt = "以下是本会话的历史对话。每条前标注了发言者：\n"
                + "【本会话历史】\n"
                + historyBlock
                + "【历史结束】\n"
                + "说明：标注为" + ChatHistoryFormatter.SELF_MARK + "的是你（当前模型）之前的发言；"
                + "标注为" + ChatHistoryFormatter.OTHER_MARK + "的是其他模型的回答，它们不是你说的话，"
                + "仅供参考，不要当作你的记忆或结论。\n"
                + "请结合上述历史与用户当前问题，以你（当前模型）的身份回答。";
        // 关键：spring-ai 的 .system(..) 会覆盖 ModelRouter 的 defaultSystem，
        // 所以这里必须把基础人设一起拼进去，否则从第二条消息起人设会被历史块顶掉。
        return chatClient.prompt()
                .system(ModelRouter.SYSTEM_PROMPT + "\n\n" + historyPrompt);
    }

    /**
     * 首次对话时先写兜底标题并落库，再提交异步 AI 标题任务。
     * 先落库是为了避免会话元数据出现 title=null 的中间状态。
     */
    private void initSessionIfNeeded(String userId, String sessionId, String question, String historyBlock) {
        if (historyBlock != null && !historyBlock.isBlank()) {
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
