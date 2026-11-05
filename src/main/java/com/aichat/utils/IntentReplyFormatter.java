package com.aichat.utils;

import com.aichat.Dto.ConfirmResult;

/**
 * 意图确认文案的格式化器（纯函数，不查库、不依赖 Spring）。
 *
 * <p>确认弹窗已经展示了任务/记忆的详情；聊天流不插入待确认提示，
 * 确认成功后只回一句固定短回执，避免重复 AI 消息。
 */
public final class IntentReplyFormatter {

    private IntentReplyFormatter() {
    }

    /** 文本确认后的短回执：按产品要求固定为“已经定好”，不重复详情。 */
    public static String buildConfirmResultReply(ConfirmResult result) {
        return "已经定好";
    }
}
