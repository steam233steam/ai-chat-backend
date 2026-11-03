package com.aichat.Config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class MindButlerPropertiesTest {

    @Test
    void shouldAcceptShanghaiZone() {
        MindButlerProperties props = new MindButlerProperties();
        props.validate();
    }

    @Test
    void shouldRejectOtherZone() {
        MindButlerProperties props = new MindButlerProperties();
        props.setTaskZone("UTC");
        assertThatThrownBy(props::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Asia/Shanghai");
    }
}
