package com.aichat.utils;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TaskCapabilityGuardTest {

    @Test
    void shouldRejectExternalDeliveryChannels() {
        assertThat(TaskCapabilityGuard.unsupportedReason("明天晚上 8 点在 QQ 提醒我打游戏"))
                .contains("QQ", "应用内");
        // 正则里的 \s* 必须真的支持 0 个空格，否则“在QQ提醒”这种最自然的写法会漏拦
        assertThat(TaskCapabilityGuard.unsupportedReason("明天晚上 8 点在QQ提醒我打游戏"))
                .contains("QQ");
        assertThat(TaskCapabilityGuard.unsupportedReason("用微信提醒我开会")).contains("微信");
        assertThat(TaskCapabilityGuard.unsupportedReason("发短信提醒我")).contains("短信");
        assertThat(TaskCapabilityGuard.unsupportedReason("邮件通知我")).contains("邮件");
        assertThat(TaskCapabilityGuard.unsupportedReason("打电话提醒我")).contains("电话");
        assertThat(TaskCapabilityGuard.unsupportedReason("用钉钉提醒我")).contains("钉钉");
        assertThat(TaskCapabilityGuard.unsupportedReason("在飞书通知我")).contains("飞书");
        assertThat(TaskCapabilityGuard.unsupportedReason("用 Telegram 提醒我")).contains("Telegram");
        assertThat(TaskCapabilityGuard.unsupportedReason("用 WhatsApp 通知我")).contains("WhatsApp");
    }

    @Test
    void shouldNotRejectWhenChannelIsOnlyTaskContent() {
        assertThat(TaskCapabilityGuard.isSupported("提醒我查邮件")).isTrue();
        assertThat(TaskCapabilityGuard.isSupported("提醒我回邮件")).isTrue();
        assertThat(TaskCapabilityGuard.isSupported("记住我用 QQ 邮箱")).isTrue();
        assertThat(TaskCapabilityGuard.isSupported("用微信登录")).isTrue();
        assertThat(TaskCapabilityGuard.isSupported("提醒我查 QQ 消息")).isTrue();
        assertThat(TaskCapabilityGuard.isSupported("提醒我打电话给妈妈")).isTrue();
        assertThat(TaskCapabilityGuard.isSupported("提醒我付款")).isTrue();
        assertThat(TaskCapabilityGuard.isSupported("提醒我听 QQ 音乐")).isTrue();
        assertThat(TaskCapabilityGuard.isSupported("明天 9 点提醒我交作业")).isTrue();
        assertThat(TaskCapabilityGuard.isSupported(null)).isTrue();
    }

    @Test
    void shouldRejectActionsThatNeedRealWorldExecution() {
        assertThat(TaskCapabilityGuard.unsupportedReason("帮我下单买咖啡")).contains("下单");
        assertThat(TaskCapabilityGuard.unsupportedReason("替我转账 500 块")).contains("转账");
        assertThat(TaskCapabilityGuard.unsupportedReason("帮我买一杯咖啡")).contains("购买");
        assertThat(TaskCapabilityGuard.unsupportedReason("帮我想想买什么")).isNull();
    }
}
