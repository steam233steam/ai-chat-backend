package com.aichat.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

/**
 * 时间衰减的纯函数测试：只验数学，不碰 Mapper/Redis。
 * 这里定死「半衰期 = 180 天」等常量，是为了让改动公式的人立刻看到行为变化。
 */
class MemoryDecayTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 8, 12, 0);

    @Test
    void shouldHalveWeightAfterOneHalfLife() {
        double atZero = MemoryDecay.score(BigDecimal.ONE, NOW, NOW, 180);
        double after = MemoryDecay.score(BigDecimal.ONE, NOW.minusDays(180), NOW, 180);

        assertThat(atZero).isCloseTo(1.0, within(1e-9));
        assertThat(after).isCloseTo(0.5, within(1e-9));
    }

    @Test
    void shouldHalveAgainAfterTwoHalfLives() {
        // 400 天 ≈ 2.22 个半衰期 → 0.5^2.22 ≈ 0.214
        assertThat(MemoryDecay.score(BigDecimal.ONE, NOW.minusDays(360), NOW, 180))
                .isCloseTo(0.25, within(1e-9));
    }

    @Test
    void shouldDecayDynamicFasterThanStable() {
        // 同一时刻、同样 90 天没被用到：dynamic(30d 半衰期) 只剩 12.5%，stable(180d) 还有 70%
        double dynamic = MemoryDecay.score(BigDecimal.ONE, NOW.minusDays(90), NOW, 30);
        double stable = MemoryDecay.score(BigDecimal.ONE, NOW.minusDays(90), NOW, 180);

        assertThat(dynamic).isCloseTo(0.125, within(1e-9));
        assertThat(stable).isCloseTo(0.7071, within(1e-3));
        assertThat(dynamic).isLessThan(stable);
    }

    @Test
    void shouldMultiplyByConfidence() {
        double score = MemoryDecay.score(new BigDecimal("0.80"), NOW.minusDays(180), NOW, 180);
        assertThat(score).isCloseTo(0.40, within(1e-9));
    }

    @Test
    void shouldNotDecayWhenNeverUsed() {
        // lastUsed 为 null：调用方已用 update_time 兜底，这里直接按 0 天算
        assertThat(MemoryDecay.score(BigDecimal.ONE, null, NOW, 180)).isCloseTo(1.0, within(1e-9));
    }

    @Test
    void shouldNotDecayWhenHalfLifeMisconfigured() {
        // 配成 0 或负数时不衰减，也不要除零
        assertThat(MemoryDecay.score(BigDecimal.ONE, NOW.minusDays(999), NOW, 0)).isCloseTo(1.0, within(1e-9));
        assertThat(MemoryDecay.score(BigDecimal.ONE, NOW.minusDays(999), NOW, -5)).isCloseTo(1.0, within(1e-9));
    }

    @Test
    void shouldTreatFutureLastUsedAsZeroDays() {
        // 时钟回拨/数据被写成未来时间时，不能把权重放大到 >1
        assertThat(MemoryDecay.score(BigDecimal.ONE, NOW.plusDays(30), NOW, 180)).isCloseTo(1.0, within(1e-9));
    }

    @Test
    void shouldReturnZeroForZeroConfidence() {
        assertThat(MemoryDecay.score(BigDecimal.ZERO, NOW, NOW, 180)).isZero();
    }
}
