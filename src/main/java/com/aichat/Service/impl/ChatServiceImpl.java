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
import org.springframework.ai.model.function.FunctionCallback;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.aichat.Config.ModelRouter;
import com.aichat.Dto.IntentOutcome;
import com.aichat.Dto.SMessage;
import com.aichat.Dto.UserDto;
import com.aichat.Exception.BusinessException;
import com.aichat.Repository.ChatSessionRepository;
import com.aichat.Service.ChatService;
import com.aichat.Service.ChatTitleService;
import com.aichat.Service.IntentService;
import com.aichat.Service.PersonaReplyService;
import com.aichat.mcp.McpToolCatalog;
import com.aichat.utils.ChatHistoryBuilder;
import com.aichat.utils.ModelOutputSanitizer;
import com.aichat.utils.MbMarkers;
import com.aichat.utils.PromptBuilder;
import com.aichat.utils.StreamingSafetyFilter;
import com.aichat.utils.UserHolder;

import reactor.core.publisher.Flux;

@Service
public class ChatServiceImpl implements ChatService {
    private static final Logger log = LoggerFactory.getLogger(ChatServiceImpl.class);

    /** 会话标题最大长度（按 Unicode 字符计） */
    private static final int TITLE_MAX_LENGTH = 20;

    /** 意图链路异常时的固定回复：不能再回退到聊天模型，否则模型可能顺着上下文假装操作成功。 */
    private static final String INTENT_FAILURE_FALLBACK =
            "刚才的操作没有完成，请稍后重试；如果涉及任务或记忆，请刷新后确认当前状态。";

    /** 模型返回空内容时的兜底，避免把 null/空 assistant 消息写入历史。 */
    private static final String EMPTY_MODEL_FALLBACK = "（模型未返回内容，请稍后重试）";

    /** 送给模型的历史消息条数上限；<=0 表示不裁剪 */
    @Value("${app.chat.context-max-messages:20}")
    private int contextMaxMessages = 20;

    /** 送给模型的历史总字符预算；<=0 表示不限制 */
    @Value("${app.chat.context-max-chars:24000}")
    private int contextMaxChars = 24000;

    private final ChatSessionRepository chatSessionRepository;
    private final ModelRouter modelRouter;
    private final ChatTitleService chatTitleService;
    private final PromptBuilder promptBuilder;
    private final IntentService intentService;
    private final PersonaReplyService personaReplyService;
    private final McpToolCatalog mcpToolCatalog;

    public ChatServiceImpl(ChatSessionRepository chatSessionRepository, ModelRouter modelRouter, ChatTitleService chatTitleService, PromptBuilder promptBuilder, IntentService intentService, PersonaReplyService personaReplyService, McpToolCatalog mcpToolCatalog) {
        this.chatSessionRepository = chatSessionRepository;
        this.modelRouter = modelRouter;
        this.chatTitleService = chatTitleService;
        this.promptBuilder = promptBuilder;
        this.intentService = intentService;
        this.personaReplyService = personaReplyService;
        this.mcpToolCatalog = mcpToolCatalog;
    }

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
        Long sourceMessageId = chatSessionRepository.saveUserMessageAndReturnId(userId, sessionid, userMessage, model);

        // M3 意图拦截（同步/流式同一套顺序，v3 4.5）：命中意图直接返回确认/结果文案，不再调聊天模型
        IntentOutcome outcome = interceptIntent(userId, sessionid, question, sourceMessageId);
        if (outcome != null) {
            // 文本确认/取消的回执由同一个会话人设生成；弹窗路径由 Controller 调用同一个服务。
            String reply = personaReceipt(outcome, sessionid);
            // 只有 pending 标记、没有可展示文案时不要写空 assistant 消息，前端也不会插入气泡。
            String cleanReply = MbMarkers.strip(reply);
            if (!cleanReply.isBlank()) {
                chatSessionRepository.save(userId, sessionid, new AssistantMessage(cleanReply), model);
            }
            return reply;
        }

        String rawResponse;
        try {
            rawResponse = withHistory(chatClient, historyMessages, question, sessionid, model)
                    .call()
                    .content();
        } catch (Exception e) {
            log.error("AI 调用失败: userId={}, sessionid={}, model={}", userId, sessionid, model, e);
            throw new BusinessException(500, "AI 服务暂时不可用，请稍后重试");
        }
        // 输出净化：剥掉模型偶尔泄漏的内部 <ds_safety> 审核标签，避免脏数据落库/进前端
        String response = safeAssistantContent(rawResponse);
        // 模型调用与落库分开处理：落库失败不能被误报成“AI 服务不可用”，
        // 否则用户会以为重试就能解决，实际可能是数据库/缓存问题。
        chatSessionRepository.save(userId, sessionid, new AssistantMessage(response), model);
        return response;
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
        Long streamSourceMessageId = chatSessionRepository.saveUserMessageAndReturnId(userId, sessionid, new UserMessage(question), model);

        // M3 意图拦截（流式与同步同一套顺序，v3 4.5）：命中意图时只发一条 data 事件后结束，不走模型流
        IntentOutcome outcome = interceptIntent(userId, sessionid, question, streamSourceMessageId);
        if (outcome != null) {
            // 文本确认/取消的回执由同一个会话人设生成；弹窗路径由 Controller 调用同一个服务。
            String reply = personaReceipt(outcome, sessionid);
            // 只有标记、没有可展示文案时不写空消息，前端收到后只弹窗、不插气泡。
            String cleanReply = MbMarkers.strip(reply);
            if (!cleanReply.isBlank()) {
                saveAssistantReply(userId, sessionid, cleanReply, model);
            }
            return Flux.just(reply);
        }

        StringBuilder fullResponse = new StringBuilder();
        AtomicBoolean failed = new AtomicBoolean(false);
        AtomicBoolean persisted = new AtomicBoolean(false);
        StreamingSafetyFilter safetyFilter = new StreamingSafetyFilter();
        Runnable persist = () -> {
            // 正常结束或客户端主动断开才保存；失败兜底文案不写入历史，避免污染下一轮上下文。
            // 必须在 onComplete/onCancel 内同步写完：否则前端收到结束事件后立刻发下一条消息，
            // 可能读到尚未落库的 assistant 回复，导致上下文缺一轮。
            if (failed.get() || !persisted.compareAndSet(false, true)) {
                return;
            }
            // 客户端断开时 concatWith(finish) 可能不会执行，这里主动把过滤器尾部缓冲冲出来，
            // 否则历史里会少最后几十个字符。
            String tail = safetyFilter.finish();
            if (!tail.isEmpty()) {
                fullResponse.append(tail);
            }
            if (fullResponse.length() > 0) {
                saveAssistantReply(userId, sessionid, fullResponse.toString(), model);
            }
        };

        return withHistory(chatClient, historyMessages, question, sessionid, model)
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
                .doOnComplete(persist)
                .doOnCancel(persist);
    }

    @Override
    public Set<String> availableModels() {
        return modelRouter.availableModels();
    }

    /**
     * M3 意图拦截（v3 4.5）：命中意图时直接返回文案；shouldContinueChat=true 表示走正常聊天模型。
     * 失败时返回固定失败文案，不能降级到聊天模型——意图链路可能已部分执行，
     * 模型顺着上下文很容易编造“已保存/已确认”。
     */
    private IntentOutcome interceptIntent(String userId, String sessionid, String question, Long sourceMessageId) {
        try {
            IntentOutcome outcome = intentService.handle(sessionid, question, sourceMessageId);
            if (outcome.shouldContinueChat()) {
                return null; // 无意图，走正常聊天
            }
            return outcome;
        } catch (Exception e) {
            // 不能回退到聊天模型：意图链路可能已经部分执行，模型顺着上下文很容易编造“已保存/已确认”。
            log.warn("intent 处理失败，返回固定失败文案: userId={}, sessionid={}", userId, sessionid, e);
            return IntentOutcome.replyOnly(INTENT_FAILURE_FALLBACK);
        }
    }

    /** 确认/取消走人设回执；其他意图保持原有文案（pending 标记/查询/兜底）。 */
    private String personaReceipt(IntentOutcome outcome, String sessionId) {
        if (outcome.isConfirmed()) {
            return personaReplyService.confirmed(sessionId);
        }
        if (outcome.isRejected()) {
            return personaReplyService.cancelled(sessionId);
        }
        return outcome.getReply();
    }

    private void saveAssistantReply(String userId, String sessionid, String content, String model) {
        try {
            // 输出净化：剥掉流式回复里可能泄漏的 <ds_safety> 内部标签，避免脏数据落库
            String clean = safeAssistantContent(content);
            chatSessionRepository.save(userId, sessionid, new AssistantMessage(clean), model);
        } catch (Exception e) {
            // 保存失败不能反向破坏已经完成的 SSE 流；下一轮会缺这条上下文，但至少不静默吞掉。
            log.error("保存助手回复失败: userId={}, sessionid={}, model={}", userId, sessionid, model, e);
        }
    }

    /** 输出净化的统一入口：null/空内容也要变成可落库、可展示的非空文本。 */
    private String safeAssistantContent(String content) {
        String clean = ModelOutputSanitizer.sanitize(content);
        return (clean == null || clean.isBlank()) ? EMPTY_MODEL_FALLBACK : clean;
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
            // 并发首条消息时，另一个请求可能先锁定了别的模型。lockModel 内部以 MySQL 为准，
            // 这里必须回读实际生效值，否则第二个请求会用自己的 model 继续聊天，破坏“会话锁模型”。
            String effective = chatSessionRepository.getModel(userId, sessionid);
            if (effective != null && !effective.equals(model)) {
                throw new BusinessException(409,
                        "该会话已锁定为 " + effective + "，切换模型请新建会话");
            }
            log.info("会话锁定模型: userId={}, sessionid={}, model={}",
                    userId, sessionid, effective == null ? model : effective);
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
     * 组装本轮 Prompt：system 放"基础人设 + 当前用户长期记忆画像"（同步/流式共用），
     * 历史是真实对话角色，当前问题最后追加。
     *
     * 注入安全：画像由 PromptBuilder 拼装（JSON 序列化 + 大小写转义），
     * 基础人设 ModelRouter.SYSTEM_PROMPT 是可信数据；画像查询失败时 fail-open 只返回基础人设。
     */
    private ChatClient.ChatClientRequestSpec withHistory(
            ChatClient chatClient, List<Message> historyMessages, String question, String sessionid,
            String model) {
        List<Message> messages = new ArrayList<>(historyMessages.size() + 1);
        messages.addAll(historyMessages);
        messages.add(new UserMessage(question));
        // P1.1 会话隔离：画像注入只取"全局 + 本会话"的记忆，别的会话的人设不会带进来
        String systemPrompt = promptBuilder.build(ModelRouter.SYSTEM_PROMPT, sessionid);
        ChatClient.ChatClientRequestSpec spec = chatClient.prompt()
                .system(systemPrompt)
                .messages(messages);
        // 工具只在白名单模型上挂：本地 7B 模型没有工具模板，挂上去只会把调用打成文本
        List<FunctionCallback> functions = mcpToolCatalog.callbacksFor(model);
        if (functions.isEmpty()) {
            return spec;
        }
        return spec.functions(functions.toArray(new FunctionCallback[0]));
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
