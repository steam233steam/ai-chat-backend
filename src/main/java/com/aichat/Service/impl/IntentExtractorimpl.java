package com.aichat.Service.impl;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import com.aichat.Config.IntentExecutorConfig;
import com.aichat.Config.MindButlerProperties;
import com.aichat.Config.ModelProperties;
import com.aichat.Config.ModelRouter;
import com.aichat.Exception.BusinessException;
import com.aichat.Service.IntentExtractor;
import com.aichat.utils.IntentDetector;
import com.aichat.utils.IntentJsonParser;
import com.aichat.utils.IntentJsonParser.Parsed;
import com.aichat.utils.IntentPrompts;

import jakarta.annotation.PostConstruct;

/**
 * 意图提取器实现：用 LLM 一次调用把口语转结构化候选。
 *
 * 设计要点（v3 9.5）：
 * - 提取器显式覆盖 system prompt（IntentPrompts），不复用聊天人设；
 * - 走 DeepSeek/OpenAI 兼容协议（结构化 JSON 能力比本地 Ollama 强）；模型名可配；
 * - LLM 调用不能包在数据库事务里（v3 9.5）；失败/解析空一律返回空 Parsed，上层兜底；
 * - 独立线程池 + timeout，防止模型卡住占满 Tomcat 请求线程；
 * - 时钟注入（Clock bean）让"当前时间"可测试。
 */
@Service
public class IntentExtractorimpl implements IntentExtractor {
    private static final Logger log = LoggerFactory.getLogger(IntentExtractorimpl.class);

    @Autowired
    private ModelRouter modelRouter;
    @Autowired
    private ModelProperties modelProperties;
    @Autowired
    private MindButlerProperties props;
    @Autowired
    private Clock clock;
    @Autowired
    @Qualifier(IntentExecutorConfig.INTENT_EXECUTOR)
    private ExecutorService intentExecutor;

    @PostConstruct
    void logIntentConfig() {
        String model = props.getIntentExtractModel();
        log.info("MindButler intent extract model={}, timeout={}ms, dailyLimit={}",
                model, props.getIntentTimeoutMs(), props.getIntentDailyLimit());
        if (!props.isEnabled() || !props.isIntentEnabled()) {
            log.info("MindButler intent disabled, skip extract model whitelist check");
            return;
        }
        try {
            modelRouter.resolve(model);
        } catch (BusinessException e) {
            // 配置错误在启动时暴露，而不是等用户聊天时才 500/降级
            throw new IllegalStateException(
                    "app.mindbutler.intent.extract-model 不在 app.models.available 白名单: " + model, e);
        }
        if (modelProperties.isOllamaModel(model)) {
            log.warn("intent extract model 是本地 Ollama 模型，结构化输出稳定性可能下降: {}", model);
        }
    }

    @Override
    public Parsed extract(IntentDetector.Intent intent, String message) {
        try {
            String model = modelRouter.resolve(props.getIntentExtractModel());
            ChatClient client = modelRouter.client(model);
            String system = IntentPrompts.buildSystemPrompt(
                    intent,
                    message,
                    LocalDateTime.now(clock));

            String raw = callWithTimeout(
                    () -> client.prompt().system(system).user(message).call().content(),
                    props.getIntentTimeoutMs());
            if (raw == null || raw.isBlank()) {
                return new Parsed(List.of(), List.of());
            }
            Parsed parsed = IntentJsonParser.parse(raw);
            // 不打印 raw：可能包含用户隐私原文，只记录条数
            log.debug("intent 提取: intent={}, memories={}, tasks={}",
                    intent, parsed.memories.size(), parsed.tasks.size());
            return parsed;
        } catch (Exception e) {
            // LLM 不可用/超时/模型配置异常 → 空候选，上层走固定兜底文案（不 500）
            log.warn("intent 提取失败，返回空候选: {}", e.getMessage());
            return new Parsed(List.of(), List.of());
        }
    }

    /**
     * 包一层 Future 超时；timeoutMs<=0 表示不限制。
     * 包级可见，便于单测直接验证超时，不必 mock 整条 ChatClient 链。
     */
    String callWithTimeout(Callable<String> call, int timeoutMs) {
        Future<String> future = null;
        try {
            future = intentExecutor.submit(call);
            return timeoutMs > 0
                    ? future.get(timeoutMs, TimeUnit.MILLISECONDS)
                    : future.get();
        } catch (TimeoutException e) {
            if (future != null) {
                future.cancel(true);
            }
            log.warn("intent 提取超时（{}ms）", timeoutMs);
            return null;
        } catch (InterruptedException e) {
            if (future != null) {
                future.cancel(true);
            }
            Thread.currentThread().interrupt();
            log.warn("intent 提取被中断");
            return null;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            log.warn("intent 提取执行失败: {}", cause.getMessage());
            return null;
        } catch (Exception e) {
            log.warn("intent 提取提交失败: {}", e.getMessage());
            return null;
        }
    }
}
