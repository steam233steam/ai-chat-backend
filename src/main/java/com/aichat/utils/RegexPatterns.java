package com.aichat.utils;

/**
 * @author 虎哥
 */
public abstract class RegexPatterns {
    /**
     * 手机号正则（覆盖 13x ~ 19x 所有已开放号段，支持未来新增号段）
     * 规则：1 开头，第二位 3-9，后面 9 位数字
     */
    public static final String PHONE_REGEX = "^1[3-9]\\d{9}$";
    /**
     * 邮箱正则
     */
    public static final String EMAIL_REGEX = "^[a-zA-Z0-9_-]+@[a-zA-Z0-9_-]+(\\.[a-zA-Z0-9_-]+)+$";
    /**
     * 密码正则。4~32位的字母、数字、下划线
     */
    public static final String PASSWORD_REGEX = "^\\w{4,32}$";
    /**
     * 验证码正则, 6位数字或字母
     */
    public static final String VERIFY_CODE_REGEX = "^[a-zA-Z\\d]{6}$";

}
