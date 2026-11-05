package com.aichat.Config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.aichat.Service.SmsSender;
import com.aichat.Service.support.DisabledSmsSender;
import com.aichat.Service.support.LogSmsSender;

/**
 * 短信发送器选择。
 *
 * <p>当前没有真实供应商：
 * <ul>
 *   <li>dev-mode=true → LogSmsSender（本地日志验证码）</li>
 *   <li>dev-mode=false → DisabledSmsSender（/user/code 返回 503，不假成功）</li>
 * </ul>
 * 以后接入真实短信时，在这里根据 provider 创建对应实现即可。
 */
@Configuration
public class SmsConfig {

    @Bean
    public SmsSender smsSender(SmsProperties properties) {
        return properties.isDevMode() ? new LogSmsSender() : new DisabledSmsSender();
    }
}
