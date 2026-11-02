package com.aichat.utils;

import java.util.Arrays;
import java.util.Optional;

/**
 * 长期记忆的固定画像维度（P1 只做 4 个，小而全；tech/game 留 P2）。
 * 用 VARCHAR 列 + 本枚举校验，避免 P2 加维度时 ALTER TABLE。
 */
public enum MemoryKey {
    /** 回答/说话风格（stable） */
    STYLE("style"),
    /** 近期目标 / 在做什么（dynamic） */
    GOAL("goal"),
    /** 作息 / 习惯（多为 stable，近期安排可为 dynamic） */
    SCHEDULE("schedule"),
    /** 称呼 / 关系偏好（stable） */
    RELATION("relation");

    private final String value;

    MemoryKey(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    /** 按 DB 列值解析；非法值返回 empty（上层丢弃该候选）。 */
    public static Optional<MemoryKey> fromValue(String value) {
        return Arrays.stream(values())
                .filter(k -> k.value.equals(value))
                .findFirst();
    }

    public static boolean isValid(String value) {
        return fromValue(value).isPresent();
    }
}
