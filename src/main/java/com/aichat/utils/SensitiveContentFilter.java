package com.aichat.utils;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 写入记忆/任务前的内容净化：去掉控制字符 + 命中敏感词直接拒绝。
 * 记忆是用户输入（不可信数据），落库前必须清一遍，防 prompt injection 与敏感信息入库。
 */
public final class SensitiveContentFilter {

    /** 控制字符（保留可见文本）：去掉 \u0000-\u001F、\u007F 等。 */
    private static final Pattern CONTROL = Pattern.compile("[\\u0000-\\u001F\\u007F]");

    /** 敏感词特征：命中任意一条即拒绝入库（记忆/任务的 value 里不该出现这些）。 */
    private static final List<Pattern> SENSITIVE = List.of(
            Pattern.compile("(?i)(密码|口令|passwd|password)\\s*[:=：]?", Pattern.UNICODE_CHARACTER_CLASS),
            Pattern.compile("(?i)\\bapi[_\\-]?key\\b"),
            Pattern.compile("(?i)\\b(token|access[_\\-]?token|authorization)\\s*[:=：]?\\s*\\S{4,}",
                    Pattern.UNICODE_CHARACTER_CLASS),
            Pattern.compile("\\b[1-9]\\d{5}(18|19|20)\\d{2}(0[1-9]|1[0-2])(0[1-9]|[12]\\d|3[01])\\d{3}[0-9Xx]\\b"),
            Pattern.compile("\\b(?:[0-9]{4}\\s?){3}[0-9]{4}\\b"));

    private SensitiveContentFilter() {
    }

    /** 去掉控制字符并 trim；不改语义。 */
    public static String stripControlChars(String raw) {
        if (raw == null) {
            return "";
        }
        return CONTROL.matcher(raw).replaceAll("").trim();
    }

    /** 返回命中的敏感词类别（用于提示用户为什么被拒）；null 表示干净。 */
    public static String detectSensitive(String content) {
        if (content == null) {
            return null;
        }
        String c = stripControlChars(content);
        if (matches(c, SENSITIVE.get(0))) {
            return "疑似包含密码";
        }
        if (matches(c, SENSITIVE.get(1))) {
            return "疑似包含 API Key";
        }
        if (matches(c, SENSITIVE.get(2))) {
            return "疑似包含 Token";
        }
        if (matches(c, SENSITIVE.get(3))) {
            return "疑似包含身份证号";
        }
        if (matches(c, SENSITIVE.get(4))) {
            return "疑似包含银行卡号";
        }
        return null;
    }

    private static boolean matches(String text, Pattern p) {
        return p.matcher(text).find();
    }
}
