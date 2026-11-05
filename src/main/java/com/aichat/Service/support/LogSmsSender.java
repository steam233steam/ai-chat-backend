package com.aichat.Service.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aichat.Service.SmsSender;

/**
 * 本地开发用短信发送器：不真正发短信，把验证码打到日志。
 *
 * <p>只会在 app.sms.dev-mode=true 时被 SmsConfig 创建；生产默认 false，
 * 不会走到这里，避免验证码进入生产日志。
 */
public class LogSmsSender implements SmsSender {

    private static final Logger log = LoggerFactory.getLogger(LogSmsSender.class);

    @Override
    public void sendVerificationCode(String phone, String code) {
        log.warn("[DEV] 未接入短信服务，验证码仅打印在日志: phone={}, code={}", phone, code);
    }
}
