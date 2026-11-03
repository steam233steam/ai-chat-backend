package com.aichat.Config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * MindButler P1 配置（application.properties 里 app.mindbutler.* 绑定到这里）。
 *
 * M1 记忆 CRUD + M1.5 注入 + M2 任务/pending 用得到的项都放在这里；
 * 意图提取 / 推送 / 限流相关配置在 M3/M4 落地时再加，避免一开始配一堆没接线的开关。
 */
@Component
@ConfigurationProperties(prefix = "app.mindbutler")
public class MindButlerProperties {

    /** 总开关；关闭后只保留手动 CRUD，不做意图提取/注入。 */
    private boolean enabled = true;

    /** 意图提取开关；关闭后聊天不拦截（手动 CRUD 仍可用）。 */
    private boolean intentEnabled = true;

    /** 意图提取超时（毫秒）；<=0 表示不限制。 */
    private int intentTimeoutMs = 5000;

    /** 每用户每日意图提取次数；0 = 不限。 */
    private int intentDailyLimit = 50;

    /** dynamic 记忆默认有效期（天）；stable 记忆不过期。 */
    private int memoryDynamicTtlDays = 90;

    /** 每用户 active 记忆上限。 */
    private int memoryMaxPerUser = 100;

    /** 注入 stable 画像上限。 */
    private int memoryInjectMaxStable = 5;

    /** 注入 dynamic 画像上限。 */
    private int memoryInjectMaxDynamic = 5;

    /** 注入记忆总字符预算。 */
    private int memoryInjectMaxChars = 1200;

    /** P1 固定时区，启动时校验只允许 Asia/Shanghai。 */
    private String taskZone = "Asia/Shanghai";

    /** 每用户未删除任务上限（列表/创建兜底）。 */
    private int taskMaxPerUser = 500;

    /** GET /user/tasks/due 默认/最大条数。 */
    private int taskDueLimit = 50;

    /** 待确认动作（pending）有效期（分钟），过期后需重新发起。 */
    private int pendingTtlMinutes = 10;

    /** 待确认动作 payload 上限（字节）。 */
    private int pendingPayloadMaxBytes = 16384;

    /** 意图提取器用的模型名（默认 deepseek-chat，结构化 JSON 能力更强；需在 app.models.available 白名单里）。 */
    private String intentExtractModel = "deepseek-chat";

    /** 单条消息最多记忆候选（超出截断）。 */
    private int intentMaxMemories = 5;

    /** 单条消息最多任务候选（超出截断）。 */
    private int intentMaxTasks = 5;

    /** M4 评测 runner：true=真跑 LLM 统计提取准确率；false=桩模式（喂期望 JSON，链路确定性验证）。 */
    private boolean evalRealModel = false;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMemoryDynamicTtlDays() {
        return memoryDynamicTtlDays;
    }

    public void setMemoryDynamicTtlDays(int memoryDynamicTtlDays) {
        this.memoryDynamicTtlDays = memoryDynamicTtlDays;
    }

    public int getMemoryMaxPerUser() {
        return memoryMaxPerUser;
    }

    public void setMemoryMaxPerUser(int memoryMaxPerUser) {
        this.memoryMaxPerUser = memoryMaxPerUser;
    }

    public int getMemoryInjectMaxStable() {
        return memoryInjectMaxStable;
    }

    public void setMemoryInjectMaxStable(int memoryInjectMaxStable) {
        this.memoryInjectMaxStable = memoryInjectMaxStable;
    }

    public int getMemoryInjectMaxDynamic() {
        return memoryInjectMaxDynamic;
    }

    public void setMemoryInjectMaxDynamic(int memoryInjectMaxDynamic) {
        this.memoryInjectMaxDynamic = memoryInjectMaxDynamic;
    }

    public int getMemoryInjectMaxChars() {
        return memoryInjectMaxChars;
    }

    public void setMemoryInjectMaxChars(int memoryInjectMaxChars) {
        this.memoryInjectMaxChars = memoryInjectMaxChars;
    }

    public String getTaskZone() {
        return taskZone;
    }

    public void setTaskZone(String taskZone) {
        this.taskZone = taskZone;
    }

    public int getTaskMaxPerUser() {
        return taskMaxPerUser;
    }

    public void setTaskMaxPerUser(int taskMaxPerUser) {
        this.taskMaxPerUser = taskMaxPerUser;
    }

    public int getTaskDueLimit() {
        return taskDueLimit;
    }

    public void setTaskDueLimit(int taskDueLimit) {
        this.taskDueLimit = taskDueLimit;
    }

    public int getPendingTtlMinutes() {
        return pendingTtlMinutes;
    }

    public void setPendingTtlMinutes(int pendingTtlMinutes) {
        this.pendingTtlMinutes = pendingTtlMinutes;
    }

    public int getPendingPayloadMaxBytes() {
        return pendingPayloadMaxBytes;
    }

    public String getIntentExtractModel() {
        return intentExtractModel;
    }

    public void setIntentExtractModel(String intentExtractModel) {
        this.intentExtractModel = intentExtractModel;
    }

    public boolean isIntentEnabled() {
        return intentEnabled;
    }

    public void setIntentEnabled(boolean intentEnabled) {
        this.intentEnabled = intentEnabled;
    }

    public int getIntentTimeoutMs() {
        return intentTimeoutMs;
    }

    public void setIntentTimeoutMs(int intentTimeoutMs) {
        this.intentTimeoutMs = intentTimeoutMs;
    }

    public int getIntentDailyLimit() {
        return intentDailyLimit;
    }

    public void setIntentDailyLimit(int intentDailyLimit) {
        this.intentDailyLimit = intentDailyLimit;
    }

    public int getIntentMaxMemories() {
        return intentMaxMemories;
    }

    public void setIntentMaxMemories(int intentMaxMemories) {
        this.intentMaxMemories = intentMaxMemories;
    }

    public int getIntentMaxTasks() {
        return intentMaxTasks;
    }

    public void setIntentMaxTasks(int intentMaxTasks) {
        this.intentMaxTasks = intentMaxTasks;
    }

    public boolean isEvalRealModel() {
        return evalRealModel;
    }

    public void setEvalRealModel(boolean evalRealModel) {
        this.evalRealModel = evalRealModel;
    }

    public void setPendingPayloadMaxBytes(int pendingPayloadMaxBytes) {
        this.pendingPayloadMaxBytes = pendingPayloadMaxBytes;
    }

    /** P1 固定单时区；配置错误在启动时失败，避免运行期时间偏移。 */
    @jakarta.annotation.PostConstruct
    public void validate() {
        String zone = taskZone == null ? "" : taskZone.trim();
        if (!"Asia/Shanghai".equals(zone)) {
            throw new IllegalStateException(
                    "app.mindbutler.task.zone 只允许 Asia/Shanghai（P1 单时区），当前: " + taskZone);
        }
        taskZone = zone;
    }
}
