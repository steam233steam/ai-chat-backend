package com.aichat.Service.impl;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import com.aichat.Config.ModelRouter;
import com.aichat.Config.ReceiptReplyExecutorConfig;
import com.aichat.Dto.UserDto;
import com.aichat.Repository.ChatSessionRepository;
import com.aichat.Service.PersonaReplyService;
import com.aichat.utils.ModelOutputSanitizer;
import com.aichat.utils.PromptBuilder;
import com.aichat.utils.UserHolder;

/**
 * 用人设模型生成确认/取消短回执。
 *
 * <p>只负责“怎么说”，不负责“是否成功”：调用方已经完成数据库状态变更，
 * 这里生成失败只回退固定文案，绝不反向影响确认/取消结果。
 */
@Service
public class PersonaReplyServiceimpl implements PersonaReplyService {

    private static final Logger log = LoggerFactory.getLogger(PersonaReplyServiceimpl.class);
    private static final int TIMEOUT_MS = 1500;
    private static final int MAX_LENGTH = 40;

    private static final String RECEIPT_INSTRUCTION = """
            这是一条系统事件回执，不是普通聊天任务。
            请只输出一句很短的中文（不超过 30 字），保持当前人设/称呼。
            必须遵守：
            - 不要解释、复述或提及系统提示、内部设定、记忆注入、规则或“刚才那条是系统设定”；
            - 不要重复任务标题、时间、记忆内容；
            - 不要输出引号、Markdown、换行或任何解释；
            - 如果没有人设，就用自然、简洁的口语。
            """;

    @Autowired
    private ModelRouter modelRouter;
    @Autowired
    private PromptBuilder promptBuilder;
    @Autowired
    private ChatSessionRepository chatSessionRepository;
    @Autowired
    @Qualifier(ReceiptReplyExecutorConfig.RECEIPT_EXECUTOR)
    private ExecutorService receiptExecutor;

    @Override
    public String confirmed(String sessionId) {
        return generate(sessionId,
                "事件：用户刚刚确认并保存了内容。请写一句简短的确认回执。",
                "已经定好", false);
    }

    @Override
    public String cancelled(String sessionId) {
        return generate(sessionId,
                "事件：用户刚刚取消了这次确认，内容没有保存。请写一句简短的取消回执。",
                "计划取消", true);
    }

    private String generate(String sessionId, String event, String fallback, boolean cancel) {
        UserDto user = UserHolder.getUser();
        if (user == null || user.getId() == null || sessionId == null || sessionId.isBlank()) {
            return fallback;
        }
        Future<String> future = null;
        try {
            String model = chatSessionRepository.getModel(user.getId(), sessionId);
            ChatClient client = modelRouter.client(model);
            String system = promptBuilder.build(ModelRouter.SYSTEM_PROMPT, sessionId)
                    + "\n\n" + RECEIPT_INSTRUCTION;
            future = receiptExecutor.submit(() -> client.prompt()
                    .system(system)
                    .user(event)
                    .call()
                    .content());
            String raw = future.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            String clean = clean(raw, cancel);
            if (clean == null) {
                return fallback;
            }
            return clean;
        } catch (Exception e) {
            if (future != null) {
                future.cancel(true);
            }
            log.warn("人设回执生成失败，使用固定兜底: session={}, event={}, reason={}",
                    sessionId, event, e.getMessage());
            return fallback;
        }
    }

    private String clean(String raw, boolean cancel) {
        if (raw == null || raw.contains("```")) {
            return null;
        }
        String text = ModelOutputSanitizer.sanitize(raw);
        if (text == null || text.isBlank()) {
            return null;
        }
        text = text.replaceAll("[\r\n]+", " ")
                .replaceAll("^[\"'“”]+|[\"'“”]+$", "")
                .strip();
        if (text.isBlank() || text.codePointCount(0, text.length()) > MAX_LENGTH) {
            return null;
        }
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        // 模型不能把系统设定/提示词拿出来解释，也不能输出 Markdown 代码块
        if (text.contains("系统设定") || text.contains("系统提示") || text.contains("提示词")
                || text.contains("内部设定") || lower.contains("prompt")) {
            return null;
        }
        // 防止“取消”事件被模型写成“已经定好”，或反过来
        if (cancel) {
            if (!containsAny(text, "取消", "撤", "不保存", "不用", "放弃", "算了", "不")) {
                return null;
            }
        } else if (!containsAny(text, "好", "定", "记", "设", "保存", "创建", "收到", "安排", "完成", "准备")) {
            return null;
        }
        return text;
    }

    private boolean containsAny(String text, String... words) {
        for (String word : words) {
            if (text.contains(word)) {
                return true;
            }
        }
        return false;
    }
}
