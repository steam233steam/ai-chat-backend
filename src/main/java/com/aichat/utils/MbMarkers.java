package com.aichat.utils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MindButler 聊天文本标记（前端弹窗/区分聊天与任务的最小契约）。
 *
 * 命中意图提取出新 pending 时，/chat/ask 返回的文案尾部带一个稳定标记：
 * <pre>[[MB:pending=42]]</pre>
 * 前端只需正则检测这个标记：有 → 弹窗（用 pendingId 调 confirm/reject 或查单条），
 * 没有 → 普通聊天。标记是给机器读的，展示时由前端剥离。
 *
 * 为什么带 id（而不只是"有标记"）：前端一次请求即可拿到弹窗所需的全部信息
 * （pendingId + 文案），不用额外调 current。
 *
 * 纯静态工具，不注入 Spring、不查库。
 */
public final class MbMarkers {

    /** 标记常量：单条 pending 的弹窗提示，带 pendingId。 */
    public static final String PENDING_MARKER = "[[MB:pending=%d]]";

    /** 前端检测用正则：匹配 [[MB:pending=数字]]，捕获 pendingId。 */
    public static final Pattern PENDING_MARKER_REGEX =
            Pattern.compile("\\[\\[MB:pending=(\\d+)]]");

    private MbMarkers() {
    }

    /** 在文案尾部拼上 pending 标记（pendingId <= 0 时原样返回，不拼）。 */
    public static String appendPending(String text, long pendingId) {
        if (pendingId <= 0) {
            return text;
        }
        return text + "\n" + String.format(PENDING_MARKER, pendingId);
    }

    /** 只返回 pending 标记，不附带任何聊天文案（前端只弹窗，不插入 AI 气泡）。 */
    public static String pendingOnly(long pendingId) {
        return pendingId <= 0 ? "" : String.format(PENDING_MARKER, pendingId);
    }

    /** 文案里是否含 pending 标记。 */
    public static boolean hasPendingMarker(String text) {
        return text != null && PENDING_MARKER_REGEX.matcher(text).find();
    }

    /** 提取标记里的 pendingId；没有标记返回 null。 */
    public static Long pendingIdOf(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = PENDING_MARKER_REGEX.matcher(text);
        if (m.find()) {
            return Long.valueOf(m.group(1));
        }
        return null;
    }

    /** 剥掉所有 MB 标记，留干净文案（存历史/纯展示用）。 */
    public static String strip(String text) {
        if (text == null) {
            return null;
        }
        return PENDING_MARKER_REGEX.matcher(text).replaceAll("").trim();
    }
}
