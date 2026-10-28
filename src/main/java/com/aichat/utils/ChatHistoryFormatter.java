package com.aichat.utils;

import java.util.List;

import com.aichat.Dto.SMessage;

/**
 * 把"带模型标签"的会话历史格式化成喂给大模型的文本块。
 *
 * 设计目的：多模型切换时，同一会话的历史可能来自不同模型。
 * 把每条消息标注发言者，并用"当前模型名 vs 其他模型"做区分，配合系统提示词，
 * 让当前模型能明确知道"哪些是我（当前模型）说的、哪些是别的模型说的"，从而：
 *   - 共享上下文（跨模型不断裂，指代能对上）
 *   - 不乱认领（不把别的模型的话当成自己的记忆/结论）
 *
 * 防呆设计：弱模型（如本地 7B）读不懂裸标签，容易把别人的话当成自己的，
 * 所以这里给每条都标"发言者"，并对当前模型自己的历史打"（我）"标记，
 * 让模型一眼能看出"这段是我说的、那段不是我说的"。
 */
public final class ChatHistoryFormatter {

    /** 无模型标注时的占位（加 model 列之前的历史数据） */
    public static final String UNKNOWN_MODEL = "未知模型";

    /** 当前模型自己的历史发言标记 */
    public static final String SELF_MARK = "（我）";

    /** 其他模型的历史发言标记 */
    public static final String OTHER_MARK = "（其他模型）";

    private ChatHistoryFormatter() {
    }

    /**
     * 把历史消息渲染成带发言者标注的文本块。
     *
     * 输出形如（当前模型为 deepseek-chat 时）：
     * <pre>
     * 发言者: qwen2.5-coder:7b（其他模型）
     *   用户: 写个快排
     * 发言者: qwen2.5-coder:7b（其他模型）
     *   助手: public void quickSort...
     * 发言者: deepseek-chat（我）
     *   用户: 那热门模型呢
     * 发言者: deepseek-chat（我）
     *   助手: 当前热门的是...
     * </pre>
     *
     * @param messages     带 model 字段的历史消息（按时间正序）
     * @param currentModel 当前正在服务的模型名（用于区分"我"和"其他模型"，null 时全部视为其他模型）
     * @return 历史文本块；空历史时返回空串（调用方据此判断是否附带历史）
     */
    public static String format(List<SMessage> messages, String currentModel) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (SMessage msg : messages) {
            String model = msg.getModel() == null ? UNKNOWN_MODEL : msg.getModel();
            boolean isSelf = currentModel != null && currentModel.equals(model);
            String speaker = "user".equals(msg.getType()) ? "用户" : "助手";
            String text = msg.getText() == null ? "" : msg.getText().replace("\n", " ");
            String tag = isSelf ? SELF_MARK : OTHER_MARK;
            sb.append("发言者: ").append(model).append(tag).append('\n');
            sb.append(' ').append(speaker).append(": ").append(text).append('\n');
        }
        return sb.toString();
    }
}
