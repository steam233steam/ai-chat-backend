package com.aichat.utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import com.aichat.Dto.SMessage;

/**
 * 把持久化的聊天历史转换成真实的 Spring AI 消息角色。
 *
 * 安全边界：历史只能作为 UserMessage / AssistantMessage 进入模型，
 * 绝不能拼进 system prompt，否则用户历史会被提升为系统指令。
 */
public final class ChatHistoryBuilder {

    /** 每条消息的粗略固定开销，用于字符预算。 */
    private static final int MESSAGE_OVERHEAD = 16;

    private ChatHistoryBuilder() {
    }

    /**
     * @param history     按时间正序的历史
     * @param maxMessages 最多保留多少条；<=0 表示不按条数限制
     * @param maxChars    历史总字符预算；<=0 表示不按字符限制
     * @return 按时间正序的真实角色消息
     */
    public static List<Message> build(List<SMessage> history, int maxMessages, int maxChars) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }

        int from = 0;
        if (maxMessages > 0 && history.size() > maxMessages) {
            from = history.size() - maxMessages;
        }

        int budget = maxChars > 0 ? maxChars : Integer.MAX_VALUE;
        List<Message> selected = new ArrayList<>();

        // 从最新往旧取，保证超预算时优先保留最近上下文
        for (int i = history.size() - 1; i >= from; i--) {
            SMessage msg = history.get(i);
            if (!"user".equals(msg.getType()) && !"assistant".equals(msg.getType())) {
                continue;
            }
            String text = msg.getText();
            if (text == null || text.isEmpty()) {
                continue;
            }

            int cost = text.length() + MESSAGE_OVERHEAD;
            if (cost > budget) {
                if (!selected.isEmpty()) {
                    break;
                }
                // 最新一条自己就超预算：保留末尾部分，至少让模型看到最近的上下文
                int keep = budget - MESSAGE_OVERHEAD;
                if (keep <= 0) {
                    break;
                }
                text = text.substring(text.length() - keep);
                cost = text.length() + MESSAGE_OVERHEAD;
            }

            Message message = "user".equals(msg.getType())
                    ? new UserMessage(text)
                    : new AssistantMessage(text);
            selected.add(message);
            budget -= cost;
        }

        Collections.reverse(selected);
        return selected;
    }
}
