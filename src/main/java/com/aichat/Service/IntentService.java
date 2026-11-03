package com.aichat.Service;

import com.aichat.Dto.IntentOutcome;

/**
 * 意图服务：把“用户一句口语”转成“待确认动作”，并产出确认文案。
 *
 * 编排顺序（v3 4.5）：
 * 1. IntentDetector 粗筛：NONE → 返回 shouldContinueChat=true（走正常聊天）
 *    CONFIRM → PendingActionService.confirm → 返回确认结果文案
 *    REJECT → PendingActionService.reject → 返回拒绝文案
 *    MEMORY/TASK → 调 IntentExtractor 提取 → 校验 → 写 pending → 返回确认文案
 * 2. 提取失败/空候选 → 兜底文案（不让聊天 500，不污染上下文）
 */
public interface IntentService {

    /**
     * 处理用户消息的意图部分。
     *
     * @param sessionId       会话 id（pending 归属）
     * @param message         用户原话
     * @param sourceMessageId 来源消息 id（溯源；可为 null）
     * @return 处理结果；shouldContinueChat=true 时上层走正常聊天
     */
    IntentOutcome handle(String sessionId, String message, Long sourceMessageId);
}
