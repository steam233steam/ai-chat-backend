package com.aichat.Dto;

/**
 * 意图处理结果（一次性打包给 ChatService，决定“走正常聊天”还是“直接返回文案”）。
 *
 * 互斥状态（最多一个 action 为 true）：
 * - continueChat=true：没识别到意图，走正常聊天模型
 * - extracted=true：新提取出候选并写 pending，reply 是确认文案
 * - confirmed=true：确认了 pending，拿到 ConfirmResult（已落库）
 * - rejected=true：拒绝了 pending
 * - 全部 false 且 reply 非空：命中意图但无需落库/不调模型（过期、限流、提取空、冲突）
 *
 * 放在 Dto 而不是 Service：它不是业务接口，也不应该和 IntentService/IntentExtractor 混在一个包；
 * 字段全部 final，由工厂方法构造，避免调用方随意改状态。
 */
public final class IntentOutcome {

    private final boolean continueChat;
    private final boolean extracted;
    private final boolean confirmed;
    private final boolean rejected;
    private final String reply;
    private final ConfirmResult confirmResult;
    /** 命中"提取出新候选并写 pending"时的 pending id（其它分支为 null）。供前端弹窗直连 confirm/reject。 */
    private final Long pendingId;

    private IntentOutcome(boolean continueChat, boolean extracted, boolean confirmed,
            boolean rejected, String reply, ConfirmResult confirmResult, Long pendingId) {
        this.continueChat = continueChat;
        this.extracted = extracted;
        this.confirmed = confirmed;
        this.rejected = rejected;
        this.reply = reply;
        this.confirmResult = confirmResult;
        this.pendingId = pendingId;
    }

    /** 无意图：走正常聊天 */
    public static IntentOutcome chat() {
        return new IntentOutcome(true, false, false, false, null, null, null);
    }

    /** 新提取出候选并写了 pending，返回确认文案。 */
    public static IntentOutcome extracted(String reply) {
        return new IntentOutcome(false, true, false, false, reply, null, null);
    }

    /**
     * 新提取出候选并写了 pending，返回确认文案 + pending id。
     * 前端靠 pendingId 直接弹窗/调 confirm/reject，不用再查 current。
     */
    public static IntentOutcome extracted(String reply, Long pendingId) {
        return new IntentOutcome(false, true, false, false, reply, null, pendingId);
    }

    /** 确认了 pending，拿到落库结果 */
    public static IntentOutcome confirmed(ConfirmResult confirmResult, String reply) {
        return new IntentOutcome(false, false, true, false, reply, confirmResult, null);
    }

    /** 拒绝了 pending */
    public static IntentOutcome rejected(String reply) {
        return new IntentOutcome(false, false, false, true, reply, null, null);
    }

    /** 命中意图但无需落库/不调模型：直接回固定文案（过期、限流、提取空、冲突） */
    public static IntentOutcome replyOnly(String reply) {
        return new IntentOutcome(false, false, false, false, reply, null, null);
    }

    public boolean shouldContinueChat() {
        return continueChat;
    }

    public boolean isExtracted() {
        return extracted;
    }

    public boolean isConfirmed() {
        return confirmed;
    }

    public boolean isRejected() {
        return rejected;
    }

    public String getReply() {
        return reply;
    }

    public ConfirmResult getConfirmResult() {
        return confirmResult;
    }

    /** 命中"提取出新候选并写 pending"时的 pending id；其它分支为 null。 */
    public Long getPendingId() {
        return pendingId;
    }
}
