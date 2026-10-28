package com.aichat.Service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClient.CallResponseSpec;
import org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.test.util.ReflectionTestUtils;

import com.aichat.Config.ModelRouter;
import com.aichat.Dto.SMessage;
import com.aichat.Dto.UserDto;
import com.aichat.Exception.BusinessException;
import com.aichat.Repository.ChatSessionRepository;
import com.aichat.Service.ChatTitleService;
import com.aichat.utils.UserHolder;

/**
 * 回归测试：
 * 1. 同步对话不能把当前问题发送两次
 * 2. 跨模型历史以带 [模型名] 标签的 system message 注入，而非裸 List<Message>
 */
class ChatServiceimplTest {

    private ChatSessionRepository repository;
    private ChatTitleService chatTitleService;
    private ModelRouter modelRouter;
    private ChatClientRequestSpec requestSpec;
    private ChatServiceimpl service;

    @BeforeEach
    void setUp() {
        repository = mock(ChatSessionRepository.class);
        chatTitleService = mock(ChatTitleService.class);

        ChatClient chatClient = mock(ChatClient.class);
        requestSpec = mock(ChatClientRequestSpec.class);
        CallResponseSpec callResponseSpec = mock(CallResponseSpec.class);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.content()).thenReturn("你好");
        // system message 也返回 requestSpec，支持带历史上下文的调用
        when(requestSpec.system(anyString())).thenReturn(requestSpec);

        // ModelRouter 用 mock，client() 直接返回我们 mock 的 chatClient（隔离路由器本身）
        modelRouter = mock(ModelRouter.class);
        when(modelRouter.resolve(null)).thenReturn("qwen2.5-coder:7b");
        when(modelRouter.resolve("deepseek-chat")).thenReturn("deepseek-chat");
        when(modelRouter.client(anyString())).thenReturn(chatClient);

        service = new ChatServiceimpl();
        ReflectionTestUtils.setField(service, "chatSessionRepository", repository);
        ReflectionTestUtils.setField(service, "modelRouter", modelRouter);
        ReflectionTestUtils.setField(service, "chatTitleService", chatTitleService);

        UserDto user = new UserDto();
        user.setId("u1");
        user.setPhone("13800000000");
        UserHolder.saveUser(user);
    }

    @AfterEach
    void tearDown() {
        UserHolder.removeUser();
    }

    @Test
    void shouldSendLabeledHistoryOnceAndSaveAssistantReply() {
        // 历史里包含两个模型：qwen 和 deepseek，验证跨模型标签都被喂进去
        when(repository.getMessageHistory("u1", "s1")).thenReturn(List.of(
                new SMessage(1L, "上一轮", "user", "qwen2.5-coder:7b", 1000L),
                new SMessage(2L, "上一轮回复", "assistant", "qwen2.5-coder:7b", 2000L),
                new SMessage(3L, "中间提问", "user", "deepseek-chat", 3000L),
                new SMessage(4L, "中间回复", "assistant", "deepseek-chat", 4000L)));

        String answer = service.sendSession("s1", new UserMessage("新问题"), null);

        assertThat(answer).isEqualTo("你好");

        // 当前问题只通过 user(question) 进入 Prompt
        verify(requestSpec).user("新问题");

        // 历史以带发言者标注的 system message 注入，当前模型自己的历史标"（我）"，其他标"（其他模型）"
        ArgumentCaptor<String> systemCaptor = ArgumentCaptor.forClass(String.class);
        verify(requestSpec).system(systemCaptor.capture());
        String injected = systemCaptor.getValue();
        // 当前模型是默认 qwen2.5-coder:7b → 它的历史是"我"
        assertThat(injected).contains("发言者: qwen2.5-coder:7b（我）");
        // 其他模型 deepseek-chat 的历史 → "其他模型"
        assertThat(injected).contains("发言者: deepseek-chat（其他模型）");
        assertThat(injected).contains("用户: 上一轮");

        // 未显式传模型时，落库的应该是解析后的默认模型，而不是 null
        verify(repository).save("u1", "s1", new UserMessage("新问题"), "qwen2.5-coder:7b");
        verify(repository).save("u1", "s1", new AssistantMessage("你好"), "qwen2.5-coder:7b");
        // 已有历史时不应再生成标题
        verifyNoInteractions(chatTitleService);
    }

    @Test
    void shouldNotInjectSystemHistoryWhenNoHistory() {
        when(repository.getMessageHistory("u1", "s1")).thenReturn(List.of());

        service.sendSession("s1", new UserMessage("新问题"), null);

        // 无历史时不注入 system 历史块
        verify(requestSpec, never()).system(anyString());
    }

    @Test
    void shouldTrimLabeledHistoryToRecentMessagesBeforeCallingModel() {
        ReflectionTestUtils.setField(service, "contextMaxMessages", 2);
        when(repository.getMessageHistory("u1", "s1")).thenReturn(List.of(
                new SMessage(1L, "旧问题", "user", "qwen2.5-coder:7b", 1000L),
                new SMessage(2L, "旧回复", "assistant", "qwen2.5-coder:7b", 2000L),
                new SMessage(3L, "较新问题", "user", "qwen2.5-coder:7b", 3000L),
                new SMessage(4L, "较新回复", "assistant", "qwen2.5-coder:7b", 4000L)));

        service.sendSession("s1", new UserMessage("新问题"), null);

        ArgumentCaptor<String> systemCaptor = ArgumentCaptor.forClass(String.class);
        verify(requestSpec).system(systemCaptor.capture());
        String injected = systemCaptor.getValue();
        // 只保留最近 2 条
        assertThat(injected).doesNotContain("旧问题");
        assertThat(injected).contains("较新问题");
        assertThat(injected).contains("较新回复");
    }

    @Test
    void shouldReturnPagedHistoryWithIds() {
        when(repository.getMessageHistory("u1", "s1", 100L, 2))
                .thenReturn(List.of(
                        new SMessage(8L, "较早", "user", "qwen2.5-coder:7b", 1000L),
                        new SMessage(9L, "较新", "assistant", "deepseek-chat", 2000L)));

        List<Map<String, Object>> history = service.getHistory("s1", 100L, 2);

        assertThat(history).hasSize(2);
        assertThat(history.get(0))
                .containsEntry("id", 8L)
                .containsEntry("role", "user")
                .containsEntry("content", "较早")
                .containsEntry("model", "qwen2.5-coder:7b");
        assertThat(history.get(1))
                .containsEntry("id", 9L)
                .containsEntry("model", "deepseek-chat");
    }

    @Test
    void shouldCreateTitleBeforeSavingFirstUserMessage() {
        when(repository.getMessageHistory("u1", "s1")).thenReturn(List.of());

        service.sendSession("s1", new UserMessage("新问题"), null);

        InOrder order = inOrder(repository, chatTitleService);
        order.verify(repository).saveTitle("u1", "s1", "新问题");
        order.verify(chatTitleService).generateTitleAsync("u1", "s1", "新问题", "新问题");
        order.verify(repository).save("u1", "s1", new UserMessage("新问题"), "qwen2.5-coder:7b");
        order.verify(repository).save("u1", "s1", new AssistantMessage("你好"), "qwen2.5-coder:7b");
    }

    @Test
    void shouldUseExplicitModelForRouterAndPersistence() {
        when(repository.getMessageHistory("u1", "s1")).thenReturn(
                List.of(new SMessage(1L, "上一轮", "user", "qwen2.5-coder:7b", 1000L)));
        when(repository.getModel("u1", "s1")).thenReturn("deepseek-chat");

        service.sendSession("s1", new UserMessage("新问题"), "deepseek-chat");

        verify(modelRouter).resolve("deepseek-chat");
        verify(modelRouter).client("deepseek-chat");
        verify(repository).save("u1", "s1", new UserMessage("新问题"), "deepseek-chat");
        verify(repository).save("u1", "s1", new AssistantMessage("你好"), "deepseek-chat");
        // 模型一致，不应重新锁定
        verify(repository, never()).lockModel(anyString(), anyString(), anyString());
    }

    @Test
    void shouldLockNewSessionModelOnFirstMessage() {
        when(repository.getMessageHistory("u1", "s1")).thenReturn(List.of());
        when(repository.getModel("u1", "s1")).thenReturn(null);

        service.sendSession("s1", new UserMessage("新问题"), null);

        verify(repository).lockModel("u1", "s1", "qwen2.5-coder:7b");
    }

    @Test
    void shouldRejectModelSwitchOnLockedSession() {
        when(repository.getMessageHistory("u1", "s1")).thenReturn(
                List.of(new SMessage(1L, "上一轮", "user", "qwen2.5-coder:7b", 1000L)));
        when(repository.getModel("u1", "s1")).thenReturn("qwen2.5-coder:7b");

        BusinessException ex = org.junit.jupiter.api.Assertions.assertThrows(
                BusinessException.class,
                () -> service.sendSession("s1", new UserMessage("新问题"), "deepseek-chat"));

        assertThat(ex.getCode()).isEqualTo(409);
        assertThat(ex.getMessage()).contains("qwen2.5-coder:7b");
        // resolve 在 enforceModelLock 之前执行了（这是正常流程），但 client() 不应被调用
        verify(modelRouter).resolve("deepseek-chat");
        verify(modelRouter, never()).client(anyString());
        // 冲突时不应重新锁定，也不应调用 AI
        verify(repository, never()).lockModel(anyString(), anyString(), anyString());
        verifyNoInteractions(requestSpec);
    }

    @Test
    void shouldKeepBasePersonaWhenInjectingHistory() {
        when(repository.getMessageHistory("u1", "s1")).thenReturn(
                List.of(new SMessage(1L, "上一轮", "user", "qwen2.5-coder:7b", 1000L)));

        service.sendSession("s1", new UserMessage("新问题"), null);

        ArgumentCaptor<String> systemCaptor = ArgumentCaptor.forClass(String.class);
        verify(requestSpec).system(systemCaptor.capture());
        String injected = systemCaptor.getValue();

        // spring-ai 的 .system(..) 会覆盖 ChatClient.defaultSystem，
        // 所以带历史时必须显式把基础人设拼回去，否则从第二条消息起人设丢失
        assertThat(injected).contains(ModelRouter.SYSTEM_PROMPT.strip());
        assertThat(injected).contains("【本会话历史】");
        // 顺序：先人设，再历史
        assertThat(injected.indexOf("乐于助人的 AI 助手"))
                .isLessThan(injected.indexOf("【本会话历史】"));
    }

    @Test
    void shouldUseBatchModelLookupForSessionList() {
        when(repository.getSessions("u1")).thenReturn(List.of("s1", "s2"));
        when(repository.getModels("u1", List.of("s1", "s2")))
                .thenReturn(Map.of("s1", "qwen2.5-coder:7b"));
        when(repository.getTitle("u1", "s1")).thenReturn("标题1");
        when(repository.getTitle("u1", "s2")).thenReturn("标题2");

        List<Map<String, Object>> sessions = service.getSessions();

        assertThat(sessions).hasSize(2);
        assertThat(sessions.get(0)).containsEntry("model", "qwen2.5-coder:7b");
        assertThat(sessions.get(1)).containsEntry("model", null);
        // 列表里不能退化成逐会话查模型
        verify(repository, never()).getModel(anyString(), anyString());
    }
}
