package com.aichat.utils;

/**
 * 日志脱敏。日志里不出现明文手机号、token，出问题时用前几位定位人即可。
 */
public final class MaskUtils {

    private MaskUtils() {
    }

    /** 138****1234 */
    public static String phone(String phone) {
        if (phone == null || phone.length() < 7) {
            return "***";
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    /** 2278c1f0****9d3e */
    public static String token(String token) {
        if (token == null || token.length() < 12) {
            return "***";
        }
        return token.substring(0, 8) + "****" + token.substring(token.length() - 4);
    }
}
