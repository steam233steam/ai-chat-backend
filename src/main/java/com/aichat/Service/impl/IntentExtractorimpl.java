package com.aichat.Service.impl;

import java.time.Clock;
import java.time.LocalDateTime;
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
import com.aichat.Config.ModelRouter;
import com.aichat.Dto.IntentExtractionResult;
import com.aichat.Service.IntentExtractor;
import com.aichat.utils.IntentDetector;
import com.aichat.utils.IntentJsonParser;
import com.aichat.utils.IntentPrompts;

/**
 * 意图提取器实现：用 LLM 一次调用把口语转结构化候选。
 *
 * 职责：
 * - 组装提取 prompt；
 * - 独立线程池 + timeout，防止模型卡住占满 Tomcat 请求线程；
 * - 解析失败/模型不可用返回空结果，上层走固定兜底。
 *
 * 启动配置校验由 {@link com.aichat.Config.MindButlerStartupValidator} 负责。
 */
@Service
public class IntentExtractorimpl implements IntentExtractor {
    private static final Logger log = LoggerFactory.getLogger(IntentExtractorimpl.class);

    @Autowired
    private ModelRouter modelRouter;
    @Autowired
    private MindButlerProperties props;
    @Autowired
    private Clock clock;
    @Autowired
    @Qualifier(IntentExecutorConfig.INTENT_EXECUTOR)
    private ExecutorService intentExecutor;

    @Override
    public IntentExtractionResult extract(IntentDetector.Intent intent, String message) {
        try {
            String model = modelRouter.resolve(props.getIntentExtractModel());
            ChatClient client = modelRouter.client(model);
            String system = IntentPrompts.buildSystemPrompt(
                    intent,
                    LocalDateTime.now(clock));

            String raw = callWithTimeout(
                    () -> client.prompt().system(system).user(message).call().content(),
                    props.getIntentTimeoutMs());
            if (raw == null || raw.isBlank()) {
                return new IntentExtractionResult(null, null);
            }
            IntentExtractionResult parsed = IntentJsonParser.parse(raw);
            // 不打印 raw：可能包含用户隐私原文，只记录条数
            log.debug("intent 提取: intent={}, memories={}, tasks={}",
                    intent, parsed.getMemories().size(), parsed.getTasks().size());
            return parsed;
        } catch (Exception e) {
            // LLM 不可用/超时/模型配置异常 → 空候选，上层走固定兜底（不 500）
            log.warn("intent 提取失败，返回空候选: {}", e.getMessage());
            return new IntentExtractionResult(null, null);
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
