package com.aichat.utils;

import java.util.Locale;
import java.util.Set;

/**
 * 意图检测器（静态规则，不调 LLM、不注入 Spring）。
 *
 * P1 只做粗分类：判断一句用户消息属于哪一类，交给后续步骤处理：
 * - MEMORY / TASK：命中"记住/记一下/待办/明天/每周…"等触发词，走 LLM 提取
 * - CONFIRM：有 pending 且命中确认词，走 confirm
 * - REJECT：有 pending 且命中拒绝词，走 reject
 * - UPDATE：命中修改/延后/提前等表达（P1 不支持自然语言更新，固定提示）
 * - NO_PENDING：没有 pending 却明确说确认/取消，固定提示，避免模型幻想
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
        /** 查询类：问"我有哪些任务/你记住了我什么"，直接读已有数据回答，不走 LLM。 */
        QUERY,
        /** 确认：对现有 pending 说"对/确认/好"。 */
        CONFIRM,
        /** 拒绝：对现有 pending 说"不要/取消"。 */
        REJECT,
        /** 修改任务：P1 不支持自然语言更新，必须走固定提示，不能交给聊天模型。 */
        UPDATE,
        /** 没有 pending 却说确认/取消：固定提示，避免模型幻想“已更新/已保存”。 */
        NO_PENDING,
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
            // 明确的待办/提醒词
            "待办", "代办", "任务", "提醒我", "提醒", "到点", "截止",
            // 时间短语：保留"明天/后天"，去掉单字"周/交/提交"和裸"今天"，避免"今天天气"误触发
            "明天", "后天", "每天", "每周", "每隔", "每个月", "每小时",
            "点钟", "点半", "点交", "点提醒", "点前", "之前", "之后",
            // 星期/周次短语：覆盖"周五下班前""下周三开会"这类没有"点"的表达
            "周一", "周二", "周三", "周四", "周五", "周六", "周日",
            "下周", "本周", "这周",
            // "今天"只在带时段时触发（"今天早上八点吃药"），单独"今天"不触发
            "今天早上", "今天上午", "今天中午", "今天下午", "今天傍晚", "今天晚上");

    // 确认词：短促肯定
    private static final Set<String> CONFIRM_TRIGGERS = Set.of(
            "对", "对的", "确认", "好", "好的", "行", "可以", "是", "嗯", "要的",
            "确定", "没问题", "就这样", "可以吧", "ok", "OK");

    // 拒绝词：明确的否定短语（去掉单字"不/错/否"，避免"不错"误触发；只在有 pending 时生效）
    private static final Set<String> REJECT_TRIGGERS = Set.of(
            "不对", "不是", "错了", "不要", "不用", "取消", "算了吧", "算了", "改一下",
            "重说", "重来", "删掉", "不要了", "不确认", "不保存", "拒绝", "no", "NO");

    // 修改类表达：P1 不支持自然语言更新/删除任务，直接固定提示，不能让聊天模型假装“已更新”
    private static final Set<String> UPDATE_TRIGGERS = Set.of(
            "改成", "改为", "改到", "修改", "调整", "改个名", "改名", "换个时间",
            "延后", "推迟", "延到", "推迟到", "提前到");

    // 明确确认/取消词：没有 pending 时返回固定提示，避免模型顺着上下文幻想“已保存/已更新”
    private static final Set<String> NO_PENDING_TRIGGERS = Set.of(
            "确认", "确定", "确认保存", "已确认", "取消", "取消吧",
            "拒绝", "不保存", "不确认", "不对", "算了", "不用了", "不要了");

    // 查询触发词：问句特征（疑问词）+ 主题词（任务/记忆）。命中 → QUERY，直接读已有数据回答。
    // 用组合判断（疑问词 AND 主题词 同时出现），误触发率比单独用触发词低得多。
    private static final Set<String> QUERY_INTERROGATIVES = Set.of(
            "哪些", "哪个", "几个", "什么", "怎么样", "如何", "还有", "有什么", "有没有", "多少", "啥", "啥样");
    private static final Set<String> QUERY_TASK_TOPICS = Set.of(
            "任务", "待办", "日程", "计划", "提醒", "安排", "事");
    private static final Set<String> QUERY_MEMORY_TOPICS = Set.of(
            "记忆", "记住的", "记住了", "记得", "了解我", "你记", "记忆怎么样", "你了解");

    // P1.2 LLM 兜底宽松触发词：关键词粗筛 NONE 时，命中这些才过 LLM 提取（避免每条"你好"都调 LLM）。
    // 覆盖：人设指令（"你是猫娘"）、称呼指令（"叫我主人"）、状态询问（"你的记忆怎么样"）、
    //       隐含记忆（"我平时喝美式"）、隐含任务（"我下周三要出差"）。
    private static final Set<String> LOOSE_TRIGGERS = Set.of(
            // 人设 / 角色指令："你是 X"、"当 X"、"扮演 X"
            "你是", "你叫", "当个", "扮演", "你是只", "你是个", "让我", "希望你",
            // 称呼 / 关系指令："叫我 X"、"我 X"
            "叫我", "我是", "我叫", "我的", "我平时", "我一般", "我通常", "我喜欢", "我不喜欢",
            // 状态 / 查询类（关键词没命中但明显在问 AI 的数据）
            "你的记忆", "你了解我", "你记得我", "你记住了我", "你记我",
            // 时间 / 任务隐含（没有"提醒"但带时间）
            "下周一", "下周二", "下周三", "下周四", "下周五", "下周六", "下周日",
            "明天", "后天", "下个月", "下周天");

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

        // 修改类表达优先于任务创建：P1 不支持更新，避免生成重复任务
        if (matchAny(text, UPDATE_TRIGGERS)) {
            return Intent.UPDATE;
        }

        // 查询类："我有哪些任务/你记住了我什么"——疑问词 + 主题词组合命中，
        // 走确定性数据查询，不进提取流程（避免被当成"创建任务"）
        if (matchAny(text, QUERY_INTERROGATIVES)
                && (matchAny(text, QUERY_TASK_TOPICS) || matchAny(text, QUERY_MEMORY_TOPICS))) {
            return Intent.QUERY;
        }

        // 没有 pending 却明确说“确认/取消”：固定提示，不能交给聊天模型自由发挥。
        // 这里用“整句精确匹配”，避免“确认一下这个方案”这类普通聊天被误判。
        if (!hasPending && NO_PENDING_TRIGGERS.contains(normalizeCommand(text))) {
            return Intent.NO_PENDING;
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

    /**
     * P1.2 LLM 兜底判断：关键词粗筛返回 NONE 时，这条消息是否值得过一次 LLM 提取。
     * 命中 LOOSE_TRIGGERS 才返回 true（避免"你好/谢谢"这类纯聊天每条都调 LLM）。
     */
    public static boolean shouldTryLlmFallback(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String text = message.trim();
        return matchAny(text, LOOSE_TRIGGERS);
    }

    private static String normalizeCommand(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("[\\p{Punct}，。！？、\\s]+", "");
    }

    /** 触发词命中判断：去掉标点后精确匹配（触发词都是短词，contains 足够）。 */
    private static boolean matchAny(String text, Set<String> words) {
        String lowered = text.toLowerCase(Locale.ROOT);
        for (String w : words) {
            if (lowered.contains(w.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
