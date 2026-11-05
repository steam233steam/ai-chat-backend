package com.aichat.Service;

/**
 * 人设化短回执：确认后“已经定好”、取消后“计划取消”不再写死，
 * 而是用当前会话的人设生成一句很短的回执。模型失败/超时/输出不合规时
 * 回退到固定文案，保证确认/取消本身永远不被回执拖垮。
 */
public interface PersonaReplyService {

    String confirmed(String sessionId);

    String cancelled(String sessionId);
}
