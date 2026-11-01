package com.aichat.utils;

import java.util.regex.Pattern;

/**
 * 模型输出净化器（输出侧防御层）。
 *
 * 部分模型（如 DeepSeek 的推理侧安全层）在特定输入下会把内部审核标签
 * （形如 &lt;ds_safety&gt;...&lt;/ds_safety&gt;）夹带进 content。正常 API 调用下
 * 这类标签不应出现在 message.content 里。为了不让脏数据落库/进前端，
 * 在模型回复落库前统一做一道清洗。
 *
 * 这是"治标"的兜底层：标签泄漏的根因（system prompt 元指令戳中模型安全层）
 * 由 ChatServiceimpl.withHistory 的简化 prompt 从"治本"侧解决。
 * 两层都要做：改提示词解决稳定复现，净化层防住所有偶发脏输出。
 */
public final class ModelOutputSanitizer {

    private ModelOutputSanitizer() {
    }

    /** 完整标签对（DOTALL 支持跨行，标签内容里可能有换行） */
    private static final Pattern DS_SAFETY_BLOCK =
            Pattern.compile("(?s)<\\s*ds_safety[^>]*>.*?</\\s*ds_safety>\\s*");

    /** 残缺标签开头（只吐了 &lt;ds_safety 没吐闭合，从该处到末尾全丢） */
    private static final String OPEN_TAG = "<ds_safety";

    /** 清洗后为空时的兜底文案（避免存空消息） */
    private static final String FALLBACK = "（模型回复被过滤，请稍后重试或换个问法）";

    /**
     * 剥离模型回复里泄漏的安全审核标签。
     *
     * @param content 模型原始回复（null 安全）
     * @return 清洗后的文本；原文为 null 返回 null；清洗后为空返回固定兜底文案
     */
    public static String sanitize(String content) {
        if (content == null) {
            return null;
        }
        String s = DS_SAFETY_BLOCK.matcher(content).replaceAll("");
        // 残缺情况：模型只吐了 <ds_safety 开头、没吐闭合标签，从该处到末尾都丢
        int idx = s.indexOf(OPEN_TAG);
        if (idx >= 0) {
            s = s.substring(0, idx);
        }
        s = s.strip();
        return s.isEmpty() ? FALLBACK : s;
    }
}
