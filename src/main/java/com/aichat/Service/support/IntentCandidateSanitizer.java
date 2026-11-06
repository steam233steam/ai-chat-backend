package com.aichat.Service.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.aichat.Config.MindButlerProperties;
import com.aichat.Dto.MemoryCandidate;
import com.aichat.Dto.TaskCandidate;
import com.aichat.utils.MemoryKey;
import com.aichat.utils.SensitiveContentFilter;

/**
 * 意图候选净化：把 LLM 输出的不可信候选裁剪成“可写 pending”的安全集合。
 *
 * 从 IntentServiceImpl 拆出，避免编排类同时承担字段校验、长度截断、敏感词过滤。
 */
@Component
public class IntentCandidateSanitizer {
    private static final Logger log = LoggerFactory.getLogger(IntentCandidateSanitizer.class);

    private static final int MEMORY_CONTENT_MAX = 256;
    private static final int TASK_TITLE_MAX = 128;

    private final MindButlerProperties props;

    public IntentCandidateSanitizer(MindButlerProperties props) {
        this.props = props;
    }

    public List<MemoryCandidate> sanitizeMemories(List<MemoryCandidate> in) {
        List<MemoryCandidate> out = new ArrayList<>();
        if (in == null) {
            return out;
        }
        int max = Math.max(0, props.getIntentMaxMemories());
        for (MemoryCandidate c : in) {
            if (out.size() >= max) {
                break; // 超出单条消息上限，截断
            }
            if (c == null || c.getKey() == null) {
                continue;
            }
            String key = c.getKey().trim().toLowerCase(Locale.ROOT);
            if (!MemoryKey.isValid(key)) {
                continue; // 非法/缺 key 的候选丢弃
            }
            String value = SensitiveContentFilter.stripControlChars(c.getValue());
            if (value.isEmpty()) {
                continue;
            }
            int len = value.codePointCount(0, value.length());
            if (len > MEMORY_CONTENT_MAX) {
                value = value.substring(0, value.offsetByCodePoints(0, MEMORY_CONTENT_MAX));
            }
            String sensitive = SensitiveContentFilter.detectSensitive(value);
            if (sensitive != null) {
                log.warn("跳过含{}的记忆候选: key={}", sensitive, key);
                continue;
            }
            String memType = (c.getMemType() == null || c.getMemType().isBlank())
                    ? "stable" : c.getMemType().trim().toLowerCase(Locale.ROOT);
            if (!"stable".equals(memType) && !"dynamic".equals(memType)) {
                continue; // 非法 memType 丢弃，避免确认时才 400
            }
            c.setKey(key);
            c.setValue(value);
            c.setMemType(memType);
            // P1.1 会话隔离：scope 只允许 global/session，非法/缺省一律 global（安全默认）
            String scope = (c.getScope() == null || c.getScope().isBlank())
                    ? "global" : c.getScope().trim().toLowerCase(Locale.ROOT);
            if (!"session".equals(scope)) {
                scope = "global";
            }
            c.setScope(scope);
            if (c.getConfidence() != null) {
                c.setConfidence(Math.max(0.0, Math.min(1.0, c.getConfidence())));
            }
            out.add(c);
        }
        return out;
    }

    /** 是否至少有一条记忆候选因敏感词被拒绝（用于给用户明确提示，而不是“没识别出”）。 */
    public boolean containsSensitiveMemory(List<MemoryCandidate> in) {
        if (in == null) {
            return false;
        }
        for (MemoryCandidate c : in) {
            if (c != null && SensitiveContentFilter.detectSensitive(c.getValue()) != null) {
                return true;
            }
        }
        return false;
    }

    /** 是否至少有一条任务候选因敏感词被拒绝（用于明确提示，而不是“没识别出”）。 */
    public boolean containsSensitiveTask(List<TaskCandidate> in) {
        if (in == null) {
            return false;
        }
        for (TaskCandidate c : in) {
            if (c != null && SensitiveContentFilter.detectSensitiveData(c.getTitle()) != null) {
                return true;
            }
        }
        return false;
    }

    public List<TaskCandidate> sanitizeTasks(List<TaskCandidate> in) {
        List<TaskCandidate> out = new ArrayList<>();
        if (in == null) {
            return out;
        }
        int max = Math.max(0, props.getIntentMaxTasks());
        for (TaskCandidate c : in) {
            if (out.size() >= max) {
                break; // 超出单条消息上限，截断
            }
            if (c == null) {
                continue;
            }
            String title = SensitiveContentFilter.stripControlChars(c.getTitle());
            if (title.isEmpty()) {
                continue;
            }
            int len = title.codePointCount(0, title.length());
            if (len > TASK_TITLE_MAX) {
                title = title.substring(0, title.offsetByCodePoints(0, TASK_TITLE_MAX));
            }
            String sensitive = SensitiveContentFilter.detectSensitiveData(title);
            if (sensitive != null) {
                log.warn("跳过含{}的任务候选: title 已脱敏", sensitive);
                continue;
            }
            // P1 只允许 create（update/delete 留 P1.5）
            String action = (c.getAction() == null || c.getAction().isBlank())
                    ? "create" : c.getAction().trim().toLowerCase(Locale.ROOT);
            if (!"create".equals(action)) {
                continue;
            }
            String recurrence = (c.getRecurrence() == null || c.getRecurrence().isBlank())
                    ? "once" : c.getRecurrence().trim().toLowerCase(Locale.ROOT);
            if (!"once".equals(recurrence) && !"daily".equals(recurrence) && !"weekly".equals(recurrence)) {
                continue;
            }
            String rule = null;
            if ("weekly".equals(recurrence)) {
                if (c.getRecurrenceRule() == null) {
                    continue; // 缺 BYDAY 的候选不要写进 pending，否则确认时才 400
                }
                rule = c.getRecurrenceRule().trim().toUpperCase(Locale.ROOT);
                if (!rule.matches("^BYDAY=(MO|TU|WE|TH|FR|SA|SU)$")) {
                    continue;
                }
            }
            if (!"once".equals(recurrence) && c.getDueAt() == null) {
                continue; // daily/weekly 必须有截止时间
            }
            String timezone = (c.getTimezone() == null || c.getTimezone().isBlank())
                    ? "Asia/Shanghai" : c.getTimezone().trim();
            if (!"Asia/Shanghai".equals(timezone)) {
                continue; // P1 固定单时区
            }
            c.setAction("create");
            c.setTitle(title);
            c.setRecurrence(recurrence);
            c.setRecurrenceRule(rule);
            c.setTimezone(timezone);
            out.add(c);
        }
        return out;
    }
}
