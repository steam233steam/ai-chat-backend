package com.aichat.Service.support;

import com.aichat.Exception.BusinessException;
import com.aichat.Service.SmsSender;

/**
 * 生产未配置短信供应商时的发送器。
 *
 * <p>ensureAvailable 会在生成验证码、占用 60 秒冷却之前抛 503，
 * 让接口诚实地告诉前端“短信服务未配置”，而不是返回“验证码已发送”。
 */
public class DisabledSmsSender implements SmsSender {

    static final String MESSAGE = "短信服务暂未配置，请使用密码登录或联系管理员";

    @Override
    public void ensureAvailable() {
        throw new BusinessException(503, MESSAGE);
    }

    @Override
    public void sendVerificationCode(String phone, String code) {
        throw new BusinessException(503, MESSAGE);
    }
}
