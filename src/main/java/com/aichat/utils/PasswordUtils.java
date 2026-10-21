package com.aichat.utils;

import cn.hutool.crypto.digest.BCrypt;

/**
 * 密码加密工具类
 * 使用 BCrypt 算法，自动加盐，安全性高
 */
public class PasswordUtils {

    /**
     * 加密密码
     * @param plainPassword 明文密码
     * @return 加密后的密码（BCrypt哈希值）
     */
    public static String encode(String plainPassword) {
        return BCrypt.hashpw(plainPassword);
    }

    /**
     * 验证密码
     * @param plainPassword 明文密码
     * @param encodedPassword 加密后的密码
     * @return 是否匹配
     */
    public static boolean matches(String plainPassword, String encodedPassword) {
        return BCrypt.checkpw(plainPassword, encodedPassword);
    }
}
