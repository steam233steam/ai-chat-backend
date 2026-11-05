package com.aichat.Config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.aichat.Service.support.DisabledSmsSender;
import com.aichat.Service.support.LogSmsSender;

class SmsConfigTest {

    private final SmsConfig config = new SmsConfig();

    @Test
    void shouldUseLogSenderOnlyInDevMode() {
        SmsProperties props = new SmsProperties();
        props.setDevMode(true);
        assertThat(config.smsSender(props)).isInstanceOf(LogSmsSender.class);
    }

    @Test
    void shouldUseDisabledSenderByDefault() {
        SmsProperties props = new SmsProperties();
        assertThat(config.smsSender(props)).isInstanceOf(DisabledSmsSender.class);
    }
}
