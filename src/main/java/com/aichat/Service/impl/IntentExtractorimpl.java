package com.aichat.Service.impl;

import java.time.Clock;
import java.time.LocalDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.aichat.Config.MindButlerProperties;
import com.aichat.Config.ModelRouter;
import com.aichat.Service.IntentExtractor;
import com.aichat.utils.IntentDetector;
import com.aichat.utils.IntentJsonParser;
import com.aichat.utils.IntentJsonParser.Parsed;
import com.aichat.utils.IntentPrompts;

/**
 * 意图提取器实现：用 LLM 一次调用把口语转结构化候选。
 *
 * 设计要点（v3 9.5）：
 * - 提取器显式覆盖 system prompt（IntentPrompts），不复用聊天人设；
 * - 走 DeepSeek/OpenAI 兼容协议（结构化 JSON 能力比本地 Ollama 强）；模型名可配；
 * - LLM 调用不能包在数据库事务里（v3 9.5）；失败/解析空一律返回空 Parsed，上层兜底；
 * - 时钟注入（Clock bean）让"当前时间"可测试。
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

    @Override
    public Parsed extract(IntentDetector.Intent intent, String message) {
        // 粗筛意图为 NONE 时不该进提取器（IntentService 会先挡）；这里兜底仍返回空
        String modelName = props.getIntentExtractModel();
        // 提取器走非 Ollama 模型；resolve 校验白名单
        String model = modelRouter.resolve(modelName);
        ChatClient client = modelRouter.client(model);

        String system = IntentPrompts.buildSystemPrompt(
                intent,
                message,
                LocalDateTime.now(clock));

        try {
            String raw = client.prompt()
                    .system(system)
                    .user(message)
                    .call()
                    .content();
            Parsed parsed = IntentJsonParser.parse(raw);
            log.debug("intent 提取: intent={}, raw={}, memories={}, tasks={}",
                    intent, raw, parsed.memories.size(), parsed.tasks.size());
            return parsed;
        } catch (Exception e) {
            // LLM 不可用 → 空候选，上层走兜底文案（v3 9.5：不 500，不污染聊天）
            log.warn("intent 提取失败，返回空候选: {}", e.getMessage());
            return new Parsed(java.util.List.of(), java.util.List.of());
        }
    }
}
