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
     * @param intent   本次要提取的意图（MEMORY / TASK / null），null 表示宽松兜底让 LLM 自行判断
     * @param now      当前时间（Asia/Shanghai），供 LLM 解析相对时间
     */
    public static String buildSystemPrompt(
            IntentDetector.Intent intent, LocalDateTime now) {
        String nowStr = FMT.format(now);
        StringBuilder sb = new StringBuilder();
        sb.append("你是 MindButler 的意图提取器，任务是把用户一句口语转成结构化候选，供用户二次确认后落库。\n");
        sb.append("当前时间：").append(nowStr).append("（Asia/Shanghai）。\n");
        sb.append("严格只输出一个 JSON 对象，禁止 markdown 围栏、禁止解释文字。\n\n");
        sb.append("输出 schema：\n");
        sb.append("{\n");
        sb.append("  \"memories\":[{\"key\":\"style|goal|schedule|relation\",\"value\":\"简短内容\",\"memType\":\"stable|dynamic\",\"scope\":\"global|session\",\"confidence\":0.0,\"replacesId\":null}],\n");
        sb.append("  \"tasks\":[{\"action\":\"create\",\"title\":\"标题\",\"dueAt\":\"yyyy-MM-ddTHH:mm:ss 或 null\",\"timezone\":\"Asia/Shanghai\",\"recurrence\":\"once|daily|weekly\",\"recurrenceRule\":\"BYDAY=XX 或 null\"}]\n");
        sb.append("}\n\n");
        sb.append("规则：\n");
        sb.append("- 识别不出候选时，对应数组留空 []，不要硬编造。\n");
        sb.append("- memories.value 控制在 30 字内，去除口语赘词；敏感信息（密码/证件/银行卡）一律不提取。\n");
        sb.append("- 相对时间（明天/下周一）必须换算成 dueAt 绝对时间；周重复（每周一）填 recurrence=weekly + recurrenceRule=BYDAY=MO。\n");
        sb.append("- 一次消息可能同时含记忆和任务，分别填进两个数组。\n");
        sb.append("- memories.key=relation：存的是 AI 的角色/人设/称呼（如\"猫娘\"、\"主人\"、\"简洁\"），不是用户指令原文。用户说\"你是猫娘\"→value=猫娘；说\"叫我主人\"→value=主人。scope 填 session。\n");
        sb.append("- memories.key=style：存回答风格偏好（简洁/详细/幽默）。key=schedule：存作息/习惯。key=goal：存近期目标。\n");
        sb.append("- memories.scope：长期习惯/偏好（style/schedule/goal、长期称呼）填 global；\n");
        sb.append("  本次会话的人设/角色/临时口吻（如\"这个会话里你是猫娘\"、\"现在你叫我主人\"）填 session。\n");
        sb.append("  拿不准就填 global。scope 只影响记忆在哪些会话生效，不影响内容。\n");
        if (intent == IntentDetector.Intent.MEMORY) {
            sb.append("- 本句偏记忆/人设：重点判断 key（style/goal/schedule/relation）与 memType（长期偏好=stable，近期动态=dynamic）。\n");
            sb.append("- 用户给 AI 定人设/角色（\"你是 X\"、\"当 X\"、\"扮演 X\"、\"叫我 X\"）→ key=relation, memType=stable, scope=session。\n");
        } else if (intent == IntentDetector.Intent.TASK) {
            sb.append("- 本句偏任务：重点解析截止时间与重复规则；一次性任务 recurrence=once，daily/weekly 必须给 dueAt。\n");
        } else {
            // P1.4 宽松兜底：不预设侧重，让 LLM 自由判断"这句是记忆、人设、任务还是混合"
            sb.append("- 本句是宽松提取：自由判断属于记忆（偏好/目标/作息/人设）还是任务（带时间/重复的提醒/计划），或两者兼有。\n");
            sb.append("- 用户给 AI 定人设/角色（\"你是 X\"、\"当 X\"、\"扮演 X\"、\"叫我 X\"）→ key=relation, memType=stable, scope=session。\n");
            sb.append("- 带时间表达（明天/下周三/每天 9 点）→ 填 tasks，dueAt 必须换算成绝对时间。\n");
            sb.append("- 不确定是否要提取时，宁可留空 []，不要硬凑候选。\n");
        }
        // 用户原话由 IntentExtractorimpl 以 UserMessage 传入，不能拼进 system prompt。
        return sb.toString();
    }
}
