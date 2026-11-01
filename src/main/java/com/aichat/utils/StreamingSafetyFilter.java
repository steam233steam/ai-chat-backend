package com.aichat.utils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 有状态的流式输出过滤器：跨 chunk 剥离模型泄漏的 <ds_safety> 标签。
 *
 * 不能对每个 chunk 单独调用 ModelOutputSanitizer，因为标签可能被拆成
 * "<ds_sa" + "fety>..."; 这里保留少量尾部缓冲，等标签完整或流结束后再决定。
 */
public final class StreamingSafetyFilter {

    private static final Pattern OPEN_TAG = Pattern.compile("(?i)<\\s*ds_safety\\b[^>]*>");
    private static final Pattern CLOSE_TAG = Pattern.compile("(?i)</\\s*ds_safety\\s*>");
    private static final int TAIL_HOLDBACK = 32;
    private static final int MAX_POTENTIAL_TAG_TAIL = 64;

    private final StringBuilder buffer = new StringBuilder();
    private boolean insideTag = false;

    /** 接收一个模型 chunk，返回可以安全发送给前端的内容（可能为空）。 */
    public String accept(String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return "";
        }
        buffer.append(chunk);
        return drain(false);
    }

    /** 流结束时调用，返回剩余安全内容；未闭合的标签内容会被丢弃。 */
    public String finish() {
        return drain(true);
    }

    private String drain(boolean end) {
        StringBuilder out = new StringBuilder();

        while (true) {
            if (!insideTag) {
                Matcher open = OPEN_TAG.matcher(buffer);
                if (open.find()) {
                    out.append(buffer, 0, open.start());
                    buffer.delete(0, open.end());
                    insideTag = true;
                    continue;
                }

                int safeLen = safePrefixLength(end);
                if (safeLen > 0) {
                    out.append(buffer, 0, safeLen);
                    buffer.delete(0, safeLen);
                }
                break;
            }

            Matcher close = CLOSE_TAG.matcher(buffer);
            if (close.find()) {
                buffer.delete(0, close.end());
                insideTag = false;
                continue;
            }

            if (end) {
                buffer.setLength(0);
            }
            break;
        }

        if (end && !insideTag && buffer.length() > 0) {
            out.append(buffer);
            buffer.setLength(0);
        }
        return out.toString();
    }

    private int safePrefixLength(boolean end) {
        int len = buffer.length();
        if (end) {
            return len;
        }

        int safe = Math.max(0, len - TAIL_HOLDBACK);

        // 如果尾部有一个没闭合的 '<'，且可能正在拼 <ds_safety...>，从它开始保留
        int lastLt = lastIndexOf('<');
        if (lastLt >= 0 && len - lastLt <= MAX_POTENTIAL_TAG_TAIL && indexOf('>', lastLt) < 0) {
            safe = Math.min(safe, lastLt);
        }
        return safe;
    }

    private int lastIndexOf(char ch) {
        for (int i = buffer.length() - 1; i >= 0; i--) {
            if (buffer.charAt(i) == ch) {
                return i;
            }
        }
        return -1;
    }

    private int indexOf(char ch, int from) {
        for (int i = from; i < buffer.length(); i++) {
            if (buffer.charAt(i) == ch) {
                return i;
            }
        }
        return -1;
    }
}
