package com.aichat.utils;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;

import com.aichat.Dto.SMessage;

class ChatHistoryBuilderTest {

    @Test
    void mapsUserAndAssistantRolesAndKeepsOrder() {
        List<Message> messages = ChatHistoryBuilder.build(List.of(
                new SMessage(1L, "问题", "user", "m1", 1L),
                new SMessage(2L, "回答", "assistant", "m1", 2L)), 20, 24000);

        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).getMessageType()).isEqualTo(MessageType.USER);
        assertThat(messages.get(0).getText()).isEqualTo("问题");
        assertThat(messages.get(1).getMessageType()).isEqualTo(MessageType.ASSISTANT);
        assertThat(messages.get(1).getText()).isEqualTo("回答");
    }

    @Test
    void skipsSystemAndEmptyMessages() {
        List<Message> messages = ChatHistoryBuilder.build(List.of(
                new SMessage(1L, "系统", "system", "m1", 1L),
                new SMessage(2L, "", "user", "m1", 2L),
                new SMessage(3L, "正常", "user", "m1", 3L)), 20, 24000);

        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).getText()).isEqualTo("正常");
    }

    @Test
    void keepsOnlyLatestMessagesByCount() {
        List<Message> messages = ChatHistoryBuilder.build(List.of(
                new SMessage(1L, "一", "user", "m1", 1L),
                new SMessage(2L, "二", "assistant", "m1", 2L),
                new SMessage(3L, "三", "user", "m1", 3L)), 2, 24000);

        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).getText()).isEqualTo("二");
        assertThat(messages.get(1).getText()).isEqualTo("三");
    }

    @Test
    void trimsByCharacterBudgetFromNewest() {
        List<Message> messages = ChatHistoryBuilder.build(List.of(
                new SMessage(1L, "OLD".repeat(50), "user", "m1", 1L),
                new SMessage(2L, "NEW".repeat(50), "assistant", "m1", 2L)), 20, 40);

        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).getMessageType()).isEqualTo(MessageType.ASSISTANT);
        assertThat(messages.get(0).getText()).hasSize(24).doesNotContain("OLD");
    }

    @Test
    void returnsEmptyForNullOrEmptyHistory() {
        assertThat(ChatHistoryBuilder.build(null, 20, 24000)).isEmpty();
        assertThat(ChatHistoryBuilder.build(List.of(), 20, 24000)).isEmpty();
    }
}
