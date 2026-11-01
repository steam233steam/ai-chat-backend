package com.aichat.utils;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 模型输出净化器单测。
 * 覆盖 <ds_safety> 标签的完整泄漏、残缺（只开头没闭合）、正常回复、null 各场景。
 */
class ModelOutputSanitizerTest {

    @Test
    @DisplayName("正常回复原样保留")
    void normalContentPassesThrough() {
        assertThat(ModelOutputSanitizer.sanitize("你好，有什么可以帮你？"))
                .isEqualTo("你好，有什么可以帮你？");
    }

    @Test
    @DisplayName("剥掉完整 <ds_safety> 标签对（跨行）")
    void stripsFullSafetyBlock() {
        String content = "我是一个乐于助"
                + "<ds_safety>[用户未成年]否\n[分类]其他\n[判定]安全。\n[规则]无</ds_safety>"
                + "Safe 的 AI 助手。";
        String cleaned = ModelOutputSanitizer.sanitize(content);
        assertThat(cleaned).doesNotContain("ds_safety");
        assertThat(cleaned).contains("Safe 的 AI 助手");
    }

    @Test
    @DisplayName("残缺标签（只吐开头没闭合）从该处到末尾全丢")
    void stripsTruncatedOpeningTag() {
        // 模型抽风只吐了标签开头，后面整段是审核内容，没有 </ds_safety>
        String content = "我是<ds_safety>[判定]安全。\n[规则]无";
        String cleaned = ModelOutputSanitizer.sanitize(content);
        assertThat(cleaned).doesNotContain("ds_safety");
        assertThat(cleaned).startsWith("我是");
    }

    @Test
    @DisplayName("整段都是标签 → 返回固定兜底文案（不存空消息）")
    void emptyAfterSanitizeFallsBack() {
        String content = "<ds_safety>[判定]安全。</ds_safety>";
        String cleaned = ModelOutputSanitizer.sanitize(content);
        assertThat(cleaned).isNotBlank();
        assertThat(cleaned).doesNotContain("ds_safety");
        assertThat(cleaned).contains("被过滤");
    }

    @Test
    @DisplayName("null 安全：返回 null")
    void nullReturnsNull() {
        assertThat(ModelOutputSanitizer.sanitize(null)).isNull();
    }

    @Test
    @DisplayName("标签夹在正常文字中间 → 只剥标签保留正文")
    void stripsTagBetweenText() {
        String content = "前半段<ds_safety>审核标记</ds_safety>后半段";
        String cleaned = ModelOutputSanitizer.sanitize(content);
        assertThat(cleaned).isEqualTo("前半段后半段");
    }
}
