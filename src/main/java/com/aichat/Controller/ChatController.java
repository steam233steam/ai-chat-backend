package com.aichat.Controller;

import java.util.List;
import java.util.Map;

import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.util.Assert;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.aichat.Dto.Result;
import com.aichat.Service.ChatService;

import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/chat")
public class ChatController {

    private static final int MAX_SESSION_ID_LENGTH = 64;
    private static final int MAX_QUESTION_LENGTH = 8_000;
    private static final int MAX_TITLE_LENGTH = 50;

    @Autowired
    private ChatService chatService;

    @PostMapping("/ask")
    public Result<String> chat(@RequestParam String sessionid, @RequestParam String question) {
        validateSessionId(sessionid);
        validateQuestion(question);
        return Result.success(chatService.sendSession(sessionid, new UserMessage(question)));
    }

    // 流式输出（SSE）
    @GetMapping(value = "/ask-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatStream(@RequestParam String sessionid, @RequestParam String question) {
        validateSessionId(sessionid);
        validateQuestion(question);
        return chatService.sendSessionStream(sessionid, question);
    }

    @DeleteMapping("/clear-session")
    public Result<String> clearSession(@RequestParam String sessionid) {
        validateSessionId(sessionid);
        boolean cleared = chatService.clearSession(sessionid);
        if (!cleared) {
            return Result.error("删除失败，会话不存在或已过期");
        }
        return Result.success("删除成功");
    }

    @GetMapping("/history")
    public Result<List<Map<String, Object>>> getHistory(@RequestParam String sessionid) {
        validateSessionId(sessionid);
        return Result.success(chatService.getHistory(sessionid));
    }

    @GetMapping("/sessions")
    public Result<List<Map<String, Object>>> getSessions() {
        return Result.success(chatService.getSessions());
    }

    @PutMapping("/title")
    public Result<String> updateTitle(@RequestParam String sessionid, @RequestParam String title) {
        validateSessionId(sessionid);
        Assert.hasText(title, "title 不能为空");
        Assert.isTrue(title.strip().length() <= MAX_TITLE_LENGTH,
                "title 长度不能超过 " + MAX_TITLE_LENGTH + " 个字符");
        chatService.saveTitle(sessionid, title.strip());
        return Result.success("标题更新成功");
    }

    private void validateSessionId(String sessionid) {
        Assert.hasText(sessionid, "sessionid 不能为空");
        Assert.isTrue(sessionid.length() <= MAX_SESSION_ID_LENGTH,
                "sessionid 长度不能超过 " + MAX_SESSION_ID_LENGTH + " 个字符");
    }

    private void validateQuestion(String question) {
        Assert.hasText(question, "question 不能为空");
        Assert.isTrue(question.length() <= MAX_QUESTION_LENGTH,
                "question 长度不能超过 " + MAX_QUESTION_LENGTH + " 个字符");
    }
}
