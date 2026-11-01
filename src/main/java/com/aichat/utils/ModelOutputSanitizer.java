package com.aichat.utils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 模型输出净化器（输出侧防御层）。
 *
 * 部分模型（如 DeepSeek 的推理侧安全层）在特定输入下会把内部审核标签
 * （形如 <ds_safety>...</ds_safety>）夹带进 content。正常 API 调用下
 * 这类标签不应出现在 message.content 里。为了不让脏数据落库/进前端，
 * 在模型回复落库前统一做一道清洗。
 */
public final class ModelOutputSanitizer {

    private ModelOutputSanitizer() {
    }

    /** 完整标签对，忽略大小写、支持跨行和标签内空白 */
    private static final Pattern DS_SAFETY_BLOCK =
            Pattern.compile("(?is)<\\s*ds_safety\\b[^>]*>.*?</\\s*ds_safety\\s*>");

    /** 残缺标签开头（只吐了 <ds_safety ... 没吐闭合，从该处到末尾全丢） */
    private static final Pattern DS_SAFETY_OPEN =
            Pattern.compile("(?is)<\\s*ds_safety\\b.*$");

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
        Matcher open = DS_SAFETY_OPEN.matcher(s);
        if (open.find()) {
            s = s.substring(0, open.start());
        }
        s = s.strip();
        return s.isEmpty() ? FALLBACK : s;
    }

    /**
     * 标题专用净化：去掉安全标签、HTML、引号、换行和控制字符。
     * 返回 null 表示该输出不可用，调用方应保留兜底标题。
     */
    public static String sanitizeTitle(String title) {
        String s = sanitize(title);
        if (s == null || FALLBACK.equals(s)) {
            return null;
        }
        s = s.replaceAll("[\r\n\t\u2028\u2029]+", " ");
        s = s.replaceAll("\\s+", " ").strip();
        s = s.replaceAll("<[^>]+>", "").strip();
        s = s.replaceAll("^[\"'`“”‘’]+|[\"'`“”‘’]+$", "").strip();
        return s.isEmpty() ? null : s;
    }
}
