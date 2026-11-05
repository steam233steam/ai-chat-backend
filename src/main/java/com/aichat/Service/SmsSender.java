package com.aichat.Service;

/**
 * 短信验证码发送端口。
 *
 * <p>本地开发使用日志实现；生产未接短信供应商时使用 DisabledSmsSender，
 * 在 {@link #ensureAvailable()} 阶段直接返回 503，避免“接口说已发送、用户却收不到”。
 * 以后接入阿里云/腾讯云时，只需要新增实现并替换 SmsConfig 里的 Bean。
 */
public interface SmsSender {

    /** 发送前预检：未配置时抛 BusinessException(503)。此时还没生成验证码/占用冷却。 */
    default void ensureAvailable() {
    }

    /** 真正发送验证码；只在 ensureAvailable 通过后调用。 */
    void sendVerificationCode(String phone, String code);
}
