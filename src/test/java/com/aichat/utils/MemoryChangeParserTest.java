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

    @Test
    void shouldExposeCandidatesWhenAmbiguous() {
        List<MemoryVO> memories = List.of(
                memory(101L, "goal", "明年考公"),
                memory(102L, "goal", "今年12月考英语四级"));

        MemoryChangeParser.Resolution r = MemoryChangeParser.parse("把目标删掉", memories);

        assertThat(r.resolved()).isFalse();
        assertThat(r.candidates()).hasSize(2);
    }

    @Test
    void shouldPickCandidateByKeyword() {
        List<String> contents = List.of("明年考公", "今年12月考英语四级");

        assertThat(MemoryChangeParser.pickCandidateIndexes("考公的那个", contents)).containsExactly(0);
    }

    @Test
    void shouldPickCandidateByOrdinal() {
        List<String> contents = List.of("明年考公", "今年12月考英语四级");

        assertThat(MemoryChangeParser.pickCandidateIndexes("第 2 条", contents)).containsExactly(1);
        assertThat(MemoryChangeParser.pickCandidateIndexes("第二个", contents)).containsExactly(1);
    }

    @Test
    void shouldNotPickWhenKeywordTooWeak() {
        List<String> contents = List.of("明年考公", "今年12月考英语四级");

        // 只有一个「考」字重合，够不上阈值，宁可继续追问
        assertThat(MemoryChangeParser.pickCandidateIndexes("考", contents)).isEmpty();
        assertThat(MemoryChangeParser.pickCandidateIndexes("嗯我再想想", contents)).isEmpty();
    }

    @Test
    void shouldExtractNewValueAroundTrigger() {
        assertThat(MemoryChangeParser.extractAfterByTrigger("考公那个改成明年上岸")).isEqualTo("明年上岸");
        assertThat(MemoryChangeParser.extractAfterLoose("明年上岸")).isEqualTo("明年上岸");
    }
}
