package com.aichat.Service;

/**
 * 会话标题生成：首次对话后异步生成一个更短的标题。
 */
public interface ChatTitleService {

    /**
     * 异步生成会话标题。
     *
     * @param fallbackTitle 首条消息写入的兜底标题；只有当前标题仍等于它时才覆盖，
     *                      避免 AI 结果晚到、把用户手动修改的标题冲掉
     */
    void generateTitleAsync(String userId, String sessionId, String firstQuestion, String fallbackTitle);
}
