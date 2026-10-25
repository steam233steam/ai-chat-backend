package com.aichat.Service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import com.aichat.Config.AsyncConfig;
import com.aichat.Config.ModelRouter;
import com.aichat.Repository.ChatSessionRepository;
import com.aichat.Service.ChatTitleService;

@Service
public class ChatTitleServiceimpl implements ChatTitleService {
    private static final Logger log = LoggerFactory.getLogger(ChatTitleServiceimpl.class);

    @Autowired
    private ModelRouter modelRouter;
    @Autowired
    private ChatSessionRepository chatSessionRepository;

    private static final String TITLE_PROMPT = """
            请根据下面这段对话内容，生成一个不超过 10 个字的简短标题，
            概括用户的主要意图。只输出标题本身，不要引号、不要解释。
            """;

    @Async(AsyncConfig.CHAT_TITLE_EXECUTOR)
    public void generateTitleAsync(String userId, String sessionId, String firstQuestion, String fallbackTitle) {
        try {
            // 标题生成用默认模型（本地 Ollama，便宜快），不跟随用户选的聊天模型
            String aiTitle = modelRouter.client(null).prompt()
                    .system(TITLE_PROMPT)
                    .user(firstQuestion)
                    .call()
                    .content();
            if (aiTitle != null && !aiTitle.isBlank()) {
                String clean = aiTitle.strip();
                if (clean.codePointCount(0, clean.length()) > 10) {
                    clean = clean.substring(0, clean.offsetByCodePoints(0, 10));
                }
                // 异步任务可能晚于用户手动重命名：只有标题仍是兜底值时才覆盖
                String currentTitle = chatSessionRepository.getTitle(userId, sessionId);
                if (!java.util.Objects.equals(fallbackTitle, currentTitle)) {
                    log.info("会话标题已被用户修改，跳过 AI 标题覆盖: userId={}, sessionId={}", userId, sessionId);
                    return;
                }
                // 走 Repository 双写 MySQL + Redis，保证用户刷新侧栏时立即看到新标题
                chatSessionRepository.saveTitle(userId, sessionId, clean);
            }
        } catch (Exception e) {
            // 标题生成失败就静默保留兜底标题，绝不影响主流程
            log.warn("AI 生成标题失败: userId={}, sessionId={}", userId, sessionId, e);
        }
    }
}
