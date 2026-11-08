package com.aichat.Controller;

import java.util.List;
import java.util.Map;
import java.util.Set;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.util.Assert;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.aichat.Dto.ChatStreamRequest;
import com.aichat.Dto.Result;
import com.aichat.Dto.UserDto;
import com.aichat.Exception.BusinessException;
import com.aichat.Service.ChatService;
import com.aichat.Service.support.ChatDailyLimiter;

import reactor.core.publisher.Flux;

import com.aichat.utils.UserHolder;

@RestController
@RequestMapping("/chat")
@Tag(name = "聊天", description = "多模型对话（同步 + 流式 SSE）、会话管理、历史分页")
public class ChatController {

    private static final int MAX_SESSION_ID_LENGTH = 64;
    private static final int MAX_QUESTION_LENGTH = 8_000;
    private static final int MAX_TITLE_LENGTH = 50;
    private static final int MAX_HISTORY_PAGE_SIZE = 100;

    private final ChatService chatService;
    private final ChatDailyLimiter chatDailyLimiter;

    public ChatController(ChatService chatService, ChatDailyLimiter chatDailyLimiter) {
        this.chatService = chatService;
        this.chatDailyLimiter = chatDailyLimiter;
    }

    /** 每日对话次数上限，默认不启用（app.chat.daily-limit <= 0）。 */
    private void checkDailyLimit() {
        UserDto user = UserHolder.getUser();
        String uid = user == null ? null : user.getId();
        if (!chatDailyLimiter.tryAcquire(uid)) {
            throw new BusinessException(429, "今天的对话次数已用完，明天再来");
        }
    }

    /** 查询可用模型白名单（前端渲染下拉框用，无需登录，公开接口） */
    @Operation(summary = "查询可用模型白名单", description = "返回 app.models.available 配置的模型名，前端渲染下拉框用，公开接口无需登录")
    @GetMapping("/models")
    public Result<Set<String>> models() {
        return Result.success(chatService.availableModels());
    }

    @Operation(summary = "同步对话", description = "按会话发送消息并阻塞等待完整回复；modelName 不传则用默认模型。同一会话锁定模型，切模型返回 409")
    @PostMapping("/ask")
    public Result<String> chat(@RequestParam String sessionid,
            @RequestParam String question,
            @RequestParam(required = false) String modelName) {
        validateSessionId(sessionid);
        validateQuestion(question);
        checkDailyLimit();
        return Result.success(chatService.sendSession(sessionid, new UserMessage(question), modelName));
    }

    // 流式输出（SSE）
    @Operation(summary = "流式对话（SSE，推荐用 POST）", description = "请求体传 sessionid/question/modelName，以 Server-Sent Events 流式返回；客户端断开时已生成内容仍会落库")
    @PostMapping(value = "/ask-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatStream(@RequestBody ChatStreamRequest request) {
        if (request == null) {
            throw new BusinessException("请求体不能为空");
        }
        validateSessionId(request.getSessionid());
        validateQuestion(request.getQuestion());
        checkDailyLimit();
        return chatService.sendSessionStream(request.getSessionid(), request.getQuestion(), request.getModelName());
    }

    /** 兼容老前端：问题走 query string，会进访问日志，新代码请用上面的 POST。 */
    @Deprecated
    @Operation(summary = "流式对话（SSE，GET 兼容接口）", description = "已废弃，仅为兼容旧前端保留；问题放在 query 里会进访问日志，请改用 POST")
    @GetMapping(value = "/ask-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatStreamByGet(@RequestParam String sessionid,
            @RequestParam String question,
            @RequestParam(required = false) String modelName) {
        validateSessionId(sessionid);
        validateQuestion(question);
        checkDailyLimit();
        return chatService.sendSessionStream(sessionid, question, modelName);
    }

    @Operation(summary = "删除会话", description = "清空该会话的消息与元数据（MySQL 主删 + Redis 失效）")
    @DeleteMapping("/clear-session")
    public Result<String> clearSession(@RequestParam String sessionid) {
        validateSessionId(sessionid);
        boolean cleared = chatService.clearSession(sessionid);
        if (!cleared) {
            return Result.error("删除失败，会话不存在或已过期");
        }
        return Result.success("删除成功");
    }

    @Operation(summary = "查询历史消息", description = "按会话查询消息；不传 limit 返回全量，传 limit+beforeId 做游标分页（beforeId 排他，取比它更早的消息）")
    @GetMapping("/history")
    public Result<List<Map<String, Object>>> getHistory(
            @RequestParam String sessionid,
            @RequestParam(required = false) Long beforeId,
            @RequestParam(required = false) Integer limit) {
        validateSessionId(sessionid);
        // 不传 limit 时保持旧行为：返回全量历史（现有前端无需改动）
        if (limit == null) {
            Assert.isNull(beforeId, "beforeId 需要配合 limit 使用");
            return Result.success(chatService.getHistory(sessionid));
        }
        Assert.isTrue(limit >= 1 && limit <= MAX_HISTORY_PAGE_SIZE,
                "limit 必须在 1-" + MAX_HISTORY_PAGE_SIZE + " 之间");
        Assert.isTrue(beforeId == null || beforeId > 0, "beforeId 必须为正数");
        return Result.success(chatService.getHistory(sessionid, beforeId, limit));
    }

    @Operation(summary = "查询会话列表", description = "按当前用户返回所有会话，每项含 sessionId/title/锁定模型")
    @GetMapping("/sessions")
    public Result<List<Map<String, Object>>> getSessions() {
        return Result.success(chatService.getSessions());
    }

    @Operation(summary = "更新会话标题", description = "手动重命名会话标题（AI 自动标题生成会兜底，用户改名后异步任务不覆盖）")
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
