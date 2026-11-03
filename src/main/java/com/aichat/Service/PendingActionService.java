package com.aichat.Service;

import com.aichat.Dto.ConfirmResult;
import com.aichat.Dto.PendingActionVO;

/**
 * 待确认动作服务（MindButler P1，v3 状态机）。
 *
 * 流程：M3 的 IntentExtractor 提取出记忆/任务候选后调 create() 落 pending（同时把旧 pending 置 expired）；
 * 用户确认走 confirm()（原子、幂等），按 payload 内容分 memory-only/task-only/mixed 处理；
 * 拒绝走 reject()；到点未确认由懒过期/定时清理置 expired。
 *
 * 归属安全：userId 只由实现层从 UserHolder 取；跨用户访问返回 null/幂等结果。
 */
public interface PendingActionService {

    /**
     * 创建一条待确认动作（payload 由 M3 提取器序列化；P1 手动路径下可由测试/兜底构造）。
     * 同 session 已有 pending → 先把旧的置 expired 再插入（同一事务，由调用方 @Transactional 保证）。
     *
     * @return 新 pending 的 id
     */
    long create(String sessionId, String actionType, String payload, Long sourceMessageId);

    /** 查当前 session 的 pending；没有/已过期返回 null（过期会顺手置 expired）。 */
    PendingActionVO current(String sessionId);

    /**
     * 按 id 查单条待确认动作（前端弹窗按标记里的 pendingId 拉详情）。
     * 非本人 / 不存在 → 返回 null（Controller 转 404）。
     * 已过期会顺手置 expired 并返回 null（和 current 一致的懒过期语义）。
     */
    PendingActionVO getById(long id);

    /**
     * 原子确认：仅 pending 可确认。
     * - 重复确认 → alreadyConfirmed=true（不重复建记忆/任务）
     * - 已过期/已拒绝 → 409 提示重新发起
     * 确认时按 payload 实际内容（memories/tasks）分三种落库，actionType 只用于展示。
     */
    ConfirmResult confirm(long id);

    /** 原子拒绝（幂等）。已拒绝/过期 → 409。 */
    void reject(long id);
}
