package com.aichat.Config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.aichat.Exception.BusinessException;

import jakarta.annotation.PostConstruct;

/**
 * MindButler 启动配置检查。
 *
 * 从 IntentExtractorimpl 拆出：提取器只负责“提取”，启动日志/模型白名单检查属于配置层。
 * 模型配置错误只打 ERROR 并降级（意图功能返回固定兜底），不让可选 AI 功能拖垮整个后端启动。
 */
@Component
public class MindButlerStartupValidator {
    private static final Logger log = LoggerFactory.getLogger(MindButlerStartupValidator.class);

    private final MindButlerProperties props;
    private final ModelProperties modelProperties;
    private final ModelRouter modelRouter;

    public MindButlerStartupValidator(MindButlerProperties props, ModelProperties modelProperties,
            ModelRouter modelRouter) {
        this.props = props;
        this.modelProperties = modelProperties;
        this.modelRouter = modelRouter;
    }

    @PostConstruct
    void validate() {
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
            log.error("app.mindbutler.intent.extract-model 不在 app.models.available 白名单: {}；"
                    + "意图功能将降级为固定兜底，普通聊天不受影响", model);
            return;
        }
        if (modelProperties.isOllamaModel(model)) {
            log.warn("intent extract model 是本地 Ollama 模型，结构化输出稳定性可能下降: {}", model);
        }
    }
}
