package com.aichat.utils;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 记忆的时间衰减：越久没被用到的记忆，注入时权重越低。
 *
 * <p>用半衰期而不是线性衰减，理由是三条：
 * <ol>
 *   <li>单调、有界、永远为正——不会出现「放久了变成负数、排到已删记忆后面」；</li>
 *   <li>参数好解释：「半年衰减一半」比「每天减 0.0019」更像人能记住的规则；</li>
 *   <li>和 confidence 相乘后量纲一致，都落在 (0, 1]，排序时不用再加权归一化。</li>
 * </ol>
 *
 * <p>权重永远在查询时现算，不落库：落库就一定是「上次刷新那一刻」的旧值，
 * 还得配定时任务去重算，反而多一个会出错的地方。
 */
public final class MemoryDecay {

    private MemoryDecay() {
    }

    /**
     * 计算注入权重。
     *
     * @param confidence 提取置信度（0~1）
     * @param lastUsed   上次被注入的时间；null 表示从没用过，由调用方传 update_time 兜底
     * @param now        当前时间
     * @param halfLifeDays 半衰期（天）：stable 长、dynamic 短
     * @return 权重，落在 (0, 1]
     */
    public static double score(BigDecimal confidence, LocalDateTime lastUsed, LocalDateTime now,
            int halfLifeDays) {
        double base = confidence == null ? 1.0 : confidence.doubleValue();
        if (base <= 0) {
            return 0;
        }
        if (halfLifeDays <= 0) {
            return base; // 配错成 0/负数时宁可不衰减，也不要除零或放大
        }
        double days = idleDays(lastUsed, now);
        return base * Math.pow(0.5, days / halfLifeDays);
    }

    /** 闲置天数；lastUsed 为 null 或晚于 now（时钟回拨）都当 0 天，不放大权重。 */
    public static double idleDays(LocalDateTime lastUsed, LocalDateTime now) {
        if (lastUsed == null || now == null) {
            return 0;
        }
        long seconds = Duration.between(lastUsed, now).getSeconds();
        return seconds <= 0 ? 0 : seconds / 86400.0;
    }
}
