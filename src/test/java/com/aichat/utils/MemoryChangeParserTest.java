package com.aichat.utils;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.aichat.Dto.MemoryVO;

/**
 * 记忆变更规则的边界测试。
 *
 * 这些用例的价值在于"不该猜的时候不猜"：删除不可逆，规则宁可追问也不要动错对象。
 */
class MemoryChangeParserTest {

    private static MemoryVO memory(long id, String key, String content) {
        MemoryVO vo = new MemoryVO();
        vo.setId(id);
        vo.setMemKey(key);
        vo.setContent(content);
        vo.setStatus("active");
        return vo;
    }

    @Test
    void shouldResolveDeleteByKeyAlias() {
        List<MemoryVO> memories = List.of(
                memory(1L, "style", "喜欢简洁"),
                memory(2L, "schedule", "十一点前睡"));

        MemoryChangeParser.Resolution r = MemoryChangeParser.parse("把我关于作息的记忆删掉", memories);

        assertThat(r.resolved()).isTrue();
        assertThat(r.plan().isDelete()).isTrue();
        assertThat(r.plan().targets()).extracting(MemoryVO::getId).containsExactly(2L);
        assertThat(r.plan().preview()).contains("作息").contains("十一点前睡");
    }

    @Test
    void shouldExtractNewContentAfterTrigger() {
        List<MemoryVO> memories = List.of(memory(3L, "goal", "今年考研"));

        MemoryChangeParser.Resolution r = MemoryChangeParser.parse("把目标改成明年考公", memories);

        assertThat(r.resolved()).isTrue();
        assertThat(r.plan().isDelete()).isFalse();
        assertThat(r.plan().after()).isEqualTo("明年考公");
    }

    @Test
    void shouldStripTrailingParticlesFromNewContent() {
        List<MemoryVO> memories = List.of(memory(4L, "style", "喜欢长回复"));

        MemoryChangeParser.Resolution r = MemoryChangeParser.parse("把风格改成简洁吧", memories);

        assertThat(r.plan().after()).isEqualTo("简洁");
    }

    @Test
    void shouldAskWhenMultipleMemoriesShareTheKey() {
        List<MemoryVO> memories = List.of(
                memory(5L, "goal", "今年考研"),
                memory(6L, "goal", "攒钱买车"));

        MemoryChangeParser.Resolution r = MemoryChangeParser.parse("把目标删掉", memories);

        assertThat(r.resolved()).isFalse();
        assertThat(r.clarify()).contains("2 条").contains("今年考研").contains("攒钱买车");
    }

    @Test
    void shouldMatchByContentWhenNoKeyWord() {
        List<MemoryVO> memories = List.of(
                memory(7L, "style", "早上喝黑咖啡"),
                memory(8L, "goal", "学日语"));

        MemoryChangeParser.Resolution r = MemoryChangeParser.parse("把黑咖啡那条删了", memories);

        assertThat(r.resolved()).isTrue();
        assertThat(r.plan().targets()).extracting(MemoryVO::getId).containsExactly(7L);
    }

    @Test
    void shouldAskWhenNothingMatches() {
        List<MemoryVO> memories = List.of(memory(9L, "style", "喜欢简洁"));

        MemoryChangeParser.Resolution r = MemoryChangeParser.parse("把那条删掉", memories);

        assertThat(r.resolved()).isFalse();
        assertThat(r.clarify()).isNotBlank();
    }

    @Test
    void shouldAskWhenNoMemoriesAtAll() {
        MemoryChangeParser.Resolution r = MemoryChangeParser.parse("把目标删掉", List.of());

        assertThat(r.resolved()).isFalse();
        assertThat(r.clarify()).contains("没找到");
    }

    @Test
    void shouldAskWhenUpdatedContentSameAsBefore() {
        List<MemoryVO> memories = List.of(memory(10L, "goal", "明年考公"));

        MemoryChangeParser.Resolution r = MemoryChangeParser.parse("把目标改成明年考公", memories);

        assertThat(r.resolved()).isFalse();
        assertThat(r.clarify()).contains("一样");
    }

    @Test
    void shouldAskForNewContentWhenMissing() {
        List<MemoryVO> memories = List.of(memory(11L, "style", "喜欢简洁"));

        MemoryChangeParser.Resolution r = MemoryChangeParser.parse("把风格改一下", memories);

        assertThat(r.resolved()).isFalse();
        assertThat(r.clarify()).contains("改成什么");
    }

    @Test
    void shouldIgnoreMessageWithoutChangeWords() {
        List<MemoryVO> memories = List.of(memory(12L, "style", "喜欢简洁"));

        MemoryChangeParser.Resolution r = MemoryChangeParser.parse("今天天气不错", memories);

        assertThat(r.resolved()).isFalse();
    }
}
