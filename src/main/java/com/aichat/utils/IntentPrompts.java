package com.aichat.utils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 意图提取器 LLM 的系统指令模板。
 * 提取器显式覆盖 ModelRouter 的 SYSTEM_PROMPT（v3 9.3：提取器只消费 ModelRouter，但用自己的 system）。
 *
 * 约定：
 * - 只输出一个 JSON 对象，不要代码围栏、不要解释文字
 * - memories / tasks 可为空数组（识别不出就留空，不要硬凑）
 * - memKey 只能是 style|goal|schedule|relation（MemoryKey 枚举值）
 * - recurrence 只能是 once|daily|weekly；weekly 必须带 recurrenceRule=BYDAY=XX
 * - dueAt 用 ISO 本地时间字符串 yyyy-MM-ddTHH:mm:ss；解析不了就填 null
 */
public final class IntentPrompts {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private IntentPrompts() {
    }

    /**
     * @param intent   本次要提取的意图（MEMORY / TASK），决定提示侧重
     * @param message  用户原始口语
     * @param now      当前时间（Asia/Shanghai），供 LLM 解析相对时间
     */
    public static String buildSystemPrompt(
            IntentDetector.Intent intent, String message, LocalDateTime now) {
        String nowStr = FMT.format(now);
        StringBuilder sb = new StringBuilder();
        sb.append("你是 MindButler 的意图提取器，任务是把用户一句口语转成结构化候选，供用户二次确认后落库。\n");
        sb.append("当前时间：").append(nowStr).append("（Asia/Shanghai）。\n");
        sb.append("严格只输出一个 JSON 对象，禁止 markdown 围栏、禁止解释文字。\n\n");
        sb.append("输出 schema：\n");
        sb.append("{\n");
        sb.append("  \"memories\":[{\"key\":\"style|goal|schedule|relation\",\"value\":\"简短内容\",\"memType\":\"stable|dynamic\",\"confidence\":0.0,\"replacesId\":null}],\n");
        sb.append("  \"tasks\":[{\"action\":\"create\",\"title\":\"标题\",\"dueAt\":\"yyyy-MM-ddTHH:mm:ss 或 null\",\"timezone\":\"Asia/Shanghai\",\"recurrence\":\"once|daily|weekly\",\"recurrenceRule\":\"BYDAY=XX 或 null\"}]\n");
        sb.append("}\n\n");
        sb.append("规则：\n");
        sb.append("- 识别不出候选时，对应数组留空 []，不要硬编造。\n");
        sb.append("- memories.value 控制在 30 字内，去除口语赘词；敏感信息（密码/证件/银行卡）一律不提取。\n");
        sb.append("- 相对时间（明天/下周一）必须换算成 dueAt 绝对时间；周重复（每周一）填 recurrence=weekly + recurrenceRule=BYDAY=MO。\n");
        sb.append("- 一次消息可能同时含记忆和任务，分别填进两个数组。\n");
        if (intent == IntentDetector.Intent.MEMORY) {
            sb.append("- 本句偏记忆：重点判断 key（style/goal/schedule/relation）与 memType（长期偏好=stable，近期动态=dynamic）。\n");
        } else {
            sb.append("- 本句偏任务：重点解析截止时间与重复规则；一次性任务 recurrence=once，daily/weekly 必须给 dueAt。\n");
        }
        sb.append("\n用户原话：\n").append(message);
        return sb.toString();
    }
}
