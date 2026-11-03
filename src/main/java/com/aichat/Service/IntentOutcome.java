package com.aichat.Service;

import com.aichat.Dto.ConfirmResult;

/**
 * 意图处理结果（一次性打包给 ChatService，决定"走正常聊天"还是"直接返回文案"）。
 *
 * 互斥的四种状态（只有一个 action 为 true）：
 * - shouldExtract=true：没识别到意图，走正常聊天模型
 * - extracted=true：新提取出候选并写 pending，reply 是确认文案
 * - shouldConfirm=true：确认了 pending，拿到 ConfirmResult（已落库）
 * - shouldReject=true：拒绝了 pending
 */
public class IntentOutcome {
    /** 是否需要继续走聊天模型（true = 上面都没有意图命中） */
    public boolean shouldExtract;
    /** 是否新提取出候选并写了 pending */
    public boolean extracted;
    /** 是否确认成功 */
    public boolean shouldConfirm;
    /** 是否拒绝成功 */
    public boolean shouldReject;
    /** 给前端/聊天直接展示的文案（确认文案 / 结果文案 / 兜底文案） */
    public String reply;
    /** 确认动作的结果（shouldConfirm 时有值） */
    public ConfirmResult confirmResult;

    /** 无意图：走正常聊天 */
    public static IntentOutcome chat() {
        IntentOutcome o = new IntentOutcome();
        o.shouldExtract = true;
        return o;
    }

    /** 新提取出候选并写了 pending，返回确认文案 */
    public static IntentOutcome extracted(String reply) {
        IntentOutcome o = new IntentOutcome();
        o.extracted = true;
        o.reply = reply;
        return o;
    }

    /** 确认了 pending，拿到落库结果 */
    public static IntentOutcome confirmed(ConfirmResult cr, String reply) {
        IntentOutcome o = new IntentOutcome();
        o.shouldConfirm = true;
        o.confirmResult = cr;
        o.reply = reply;
        return o;
    }

    /** 拒绝了 pending */
    public static IntentOutcome rejected(String reply) {
        IntentOutcome o = new IntentOutcome();
        o.shouldReject = true;
        o.reply = reply;
        return o;
    }

    /** 命中意图但无需落库/不调模型：直接回固定文案（过期、限流、提取空、冲突） */
    public static IntentOutcome replyOnly(String reply) {
        IntentOutcome o = new IntentOutcome();
        o.reply = reply;
        return o;
    }
}
