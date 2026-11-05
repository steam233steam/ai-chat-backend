package com.aichat.Service.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.aichat.Dto.UserDto;
import com.aichat.Repository.ChatSessionRepository;
import com.aichat.Service.PersonaReplyService;
import com.aichat.utils.UserHolder;

/**
 * 弹窗确认/取消后的短回执写入器。
 *
 * <p>只在 REST 弹窗路径使用：确认/取消由当前会话人设生成一条短回执，
 * 模型失败/超时由 PersonaReplyService 回退固定文案。
 * 文本确认/取消走 ChatService，本身会写一条短回执，不会调用这里，避免重复。
 * 回执失败只记日志，不影响已经完成的确认/取消结果。
 */
@Component
public class ConfirmationReceiptWriter {

    private static final Logger log = LoggerFactory.getLogger(ConfirmationReceiptWriter.class);

    @Autowired
    private ChatSessionRepository chatSessionRepository;
    @Autowired
    private PersonaReplyService personaReplyService;

    /** 弹窗确认后写一条人设化短回执。 */
    public void writeConfirmed(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        write(sessionId, personaReplyService.confirmed(sessionId));
    }

    /** 弹窗取消后写一条人设化短回执。 */
    public void writeCancelled(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        write(sessionId, personaReplyService.cancelled(sessionId));
    }

    private void write(String sessionId, String content) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        UserDto user = UserHolder.getUser();
        if (user == null || user.getId() == null) {
            return;
        }
        try {
            String model = chatSessionRepository.getModel(user.getId(), sessionId);
            chatSessionRepository.save(user.getId(), sessionId,
                    new AssistantMessage(content), model);
        } catch (Exception e) {
            log.warn("确认短回执写入失败（不影响确认结果）: userId={}, session={}",
                    user.getId(), sessionId, e);
        }
    }
}
