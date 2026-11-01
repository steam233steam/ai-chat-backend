package com.aichat.utils;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class StreamingSafetyFilterTest {

    private String filter(String... chunks) {
        StreamingSafetyFilter filter = new StreamingSafetyFilter();
        StringBuilder out = new StringBuilder();
        for (String chunk : chunks) {
            out.append(filter.accept(chunk));
        }
        out.append(filter.finish());
        return out.toString();
    }

    @Test
    void removesTagSplitAcrossChunks() {
        String result = filter("<ds_sa", "fety>[内部]安全</ds_sa", "fety>你好");
        assertThat(result).isEqualTo("你好");
    }

    @Test
    void preservesNormalTextWithAngleBrackets() {
        String result = filter("a < b", " and c");
        assertThat(result).isEqualTo("a < b and c");
    }

    @Test
    void removesUppercaseAndWhitespaceVariants() {
        String result = filter("前< DS_SAFETY >[内部]</ DS_SAFETY >后");
        assertThat(result).isEqualTo("前后");
    }

    @Test
    void removesTagInsideOneChunk() {
        assertThat(filter("a<ds_safety>x</ds_safety>b")).isEqualTo("ab");
    }

    @Test
    void dropsUnclosedTagContentAtEnd() {
        assertThat(filter("before<ds_safety>secret")).isEqualTo("before");
    }

    @Test
    void handlesEveryPossibleSplitPoint() {
        String text = "前<ds_safety>[内部]审核</ds_safety>后";
        for (int i = 1; i < text.length(); i++) {
            String result = filter(text.substring(0, i), text.substring(i));
            assertThat(result).as("split at %d", i).isEqualTo("前后");
        }
    }
}
