package com.aichat.Service;

import com.aichat.Dto.IntentExtractionResult;
import com.aichat.utils.IntentDetector;

/**
 * 意图提取器：一次 LLM 调用，把用户口语转成结构化候选（memories + tasks）。
 * 失败降级（LLM 不可用/输出解析不了）一律返回空结果，由上层走兜底文案，不抛异常阻断聊天。
 */
public interface IntentExtractor {

    /**
     * 提取候选。
     *
     * @param intent   粗分类意图（MEMORY / TASK / null）。null 表示宽松兜底，
     *                 让 LLM 自由判断"这句是记忆、人设、任务还是混合"，不预设侧重。
     * @param message  用户原话
     * @return 解析后的候选；空表示“没识别到”
     */
    IntentExtractionResult extract(IntentDetector.Intent intent, String message);
}
