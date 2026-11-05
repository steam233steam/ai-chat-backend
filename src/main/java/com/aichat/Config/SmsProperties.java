package com.aichat.Config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 短信配置。P1 阶段只有 dev-mode：
 * - true：使用 LogSmsSender，验证码完整打印到日志，仅限本地开发；
 * - false：使用 DisabledSmsSender，生产未接供应商时 /user/code 明确返回 503。
 */
@Component
@ConfigurationProperties(prefix = "app.sms")
public class SmsProperties {

    private boolean devMode = false;

    public boolean isDevMode() {
        return devMode;
    }

    public void setDevMode(boolean devMode) {
        this.devMode = devMode;
    }
}
