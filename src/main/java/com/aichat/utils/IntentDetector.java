package com.aichat.utils;

import java.util.Set;

/**
 * 意图检测器（静态规则，不调 LLM、不注入 Spring）。
 *
 * P1 只做粗分类：判断一句用户消息属于哪一类，交给后续步骤处理：
 * - MEMORY / TASK：命中"记住/记一下/待办/明天/每周…"等触发词，走 LLM 提取
 * - CONFIRM：有 pending 且命中确认词，走 confirm
 * - REJECT：有 pending 且命中拒绝词，走 reject
 * - NONE：普通聊天
 *
 * 设计取舍（v3 4.4/4.5）：
 * - 触发词只用于"要不要进入提取流程"的粗筛，最终结构化抽取交给 LLM；
 *   误触发最多浪费一次 LLM 调用，LLM 会输出空候选并走兜底文案，不会脏落库。
 * - 确认/拒绝词只在"当前 session 有 pending"时才有意义，无 pending 时一律 NONE（避免误把聊天当确认）。
 */
public final class IntentDetector {

    /** 意图类别。 */
    public enum Intent {
        /** 记忆类：想让我记住某个偏好/目标/作息。 */
        MEMORY,
        /** 任务类：待办/日程/重复。 */
        TASK,
        /** 确认：对现有 pending 说"对/确认/好"。 */
        CONFIRM,
        /** 拒绝：对现有 pending 说"不要/取消"。 */
        REJECT,
        /** 无关：普通聊天。 */
        NONE
    }

    private IntentDetector() {
    }

    // 记忆触发词：口语里"让我记住"的常见说法
    private static final Set<String> MEMORY_TRIGGERS = Set.of(
            "记住", "记一下", "记下", "记着", "帮我记住", "记住我",
            "我偏好", "我喜欢", "我不喜欢", "我的习惯", "我一般", "我通常");

    // 任务触发词：待办/日程/时间表达式
    private static final Set<String> TASK_TRIGGERS = Set.of(
            "待办", "代办", "任务", "提醒我", "提醒", "到点", "截止", "交", "提交",
            "明天", "今天", "后天", "周", "每天", "每周", "每个月", "每小时",
            "点钟", "点半", "点交", "点提醒", "点前", "之前", "之后");

    // 确认词：短促肯定
    private static final Set<String> CONFIRM_TRIGGERS = Set.of(
            "对", "对的", "确认", "好", "好的", "行", "可以", "是", "嗯", "要的",
            "确定", "没问题", "就这样", "可以吧", "ok", "OK");

    // 拒绝词：明确的否定短语（去掉单字"不/错/否"，避免"不错"误触发；只在有 pending 时生效）
    private static final Set<String> REJECT_TRIGGERS = Set.of(
            "不对", "不是", "错了", "不要", "不用", "取消", "算了吧", "算了", "改一下",
            "重说", "重来", "删掉", "不要了", "不确认", "不保存", "拒绝", "no", "NO");

    /**
     * 检测意图。
     *
     * @param message   用户当前消息（原始文本）
     * @param hasPending 当前 session 是否已有 pending（决定确认/拒绝词是否生效）
     * @return 意图类别；message 为空白返回 NONE
     */
    public static Intent detect(String message, boolean hasPending) {
        if (message == null || message.isBlank()) {
            return Intent.NONE;
        }
        String text = message.trim();

        // 确认/拒绝词只在有 pending 时有效，且优先判断（"对"要快过任何"任务"词）
        if (hasPending) {
            if (matchAny(text, REJECT_TRIGGERS)) {
                return Intent.REJECT;
            }
            if (matchAny(text, CONFIRM_TRIGGERS)) {
                return Intent.CONFIRM;
            }
        }

        // 任务触发优先于记忆（"明天交作业"是任务；"记住我明天要交作业"也是任务）
        if (matchAny(text, TASK_TRIGGERS)) {
            return Intent.TASK;
        }
        // 记忆触发
        if (matchAny(text, MEMORY_TRIGGERS)) {
            return Intent.MEMORY;
        }
        return Intent.NONE;
    }

    /** 触发词命中判断：去掉标点后精确匹配（触发词都是短词，contains 足够）。 */
    private static boolean matchAny(String text, Set<String> words) {
        String lowered = text.toLowerCase();
        for (String w : words) {
            if (lowered.contains(w.toLowerCase())) {
                return true;
            }
        }
        return false;
    }
}
