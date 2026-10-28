package com.aichat.utils;

public class RedisConstants {
    // 统一前缀，避免与其他项目冲突
    public static final String PREFIX = "aichat:";

    // 登录验证码 Key
    public static final String LOGIN_CODE_KEY_PREFIX = PREFIX + "login:code:";

    // 验证码发送冷却 Key（防刷）
    public static final String LOGIN_CODE_COOLDOWN_KEY_PREFIX = PREFIX + "login:code:cooldown:";

    // 登录Token Key
    public static final String LOGIN_TOKEN_KEY_PREFIX = PREFIX + "login:token:";

    // 用户会话列表 Key（按用户隔离）
    public static final String USER_SESSIONS_KEY_PREFIX = PREFIX + "chat:sessions:";

    // 单个会话消息 Key（按用户隔离）
    public static final String USER_SESSION_KEY_PREFIX = PREFIX + "chat:session:";

    // 会话标题 Key
    public static final String SESSION_TITLE_KEY_PREFIX = PREFIX + "chat:title:";

    // 会话锁定模型 Key（Hash：{userId} → {sessionId → model}，实现"会话锁模型"）
    public static final String SESSION_MODEL_KEY_PREFIX = PREFIX + "chat:sessionmodel:";

    // TTL
    public static final long CODE_TTL_MINUTES = 5;
    public static final long CODE_SEND_COOLDOWN_SECONDS = 60;
    public static final long TOKEN_TTL_DAYS = 7;
    public static final long SESSION_TTL_DAYS = 7;

    public static final String USER_ID_PREFIX = "USER_ID_";

    // 新用户标记 Key
    public static final String NEW_USER_MARK_KEY_PREFIX = PREFIX + "user:new:";

    // 新用户标记 TTL（永久，直到用户主动取消）
    public static final long NEW_USER_MARK_TTL_DAYS = 365;
}
