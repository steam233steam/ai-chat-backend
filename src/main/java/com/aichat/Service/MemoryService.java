package com.aichat.Service;

import java.util.List;

import com.aichat.Dto.CreateMemoryRequest;
import com.aichat.Dto.MemoryVO;
import com.aichat.Dto.UpdateMemoryRequest;

/**
 * 用户长期记忆服务。
 *
 * P1 范围：手动 CRUD + 注入查询（active 且未过期）+ 软删除 + 导出。
 * 归属安全：userId 只由实现层从 UserHolder 取，方法不暴露 userId 参数给 Controller 直接传。
 * 跨用户访问统一返回 null（Controller 转 404，避免枚举）。
 */
public interface MemoryService {

    /** 查当前用户全部 active 且未过期记忆，按 stable/dynamic 分开配额取（stable 优先）。 */
    List<MemoryVO> listForInjection(int stableLimit, int dynamicLimit);

    /**
     * 会话隔离版注入查询（P1.1）：只取"全局(session_id=NULL) + 指定会话"的记忆。
     * 会话 A 里设定的猫娘人设不会注入到会话 B；sessionScope=null 等价于全量。
     */
    List<MemoryVO> listForInjection(int stableLimit, int dynamicLimit, String sessionScope);

    /** 查当前用户记忆列表（含 active+superseded，排除 deleted；limit 1-100）。 */
    List<MemoryVO> list(int limit);

    /**
     * 会话隔离版记忆列表（P1.3）：只返回"全局(session_id=NULL) + 指定会话"的记忆。
     * sessionScope=null 等价于 {@link #list(int)}（全量）。
     */
    List<MemoryVO> list(int limit, String sessionScope);

    /** 导出当前用户全部未删除记忆（含 superseded），供隐私/迁移。 */
    List<MemoryVO> export();

    /** 查某条记忆；非本人或已软删返回 null。 */
    MemoryVO get(long id);

    /**
     * 手动创建。source=manual、confidence=1.00。
     * 同一用户已有完全相同的 active 内容 → 409（BusinessException code=409）。
     */
    MemoryVO create(CreateMemoryRequest request);

    /** 更新（乐观锁）。version 不匹配 → 409。返回更新后的 VO。 */
    MemoryVO update(long id, UpdateMemoryRequest request);

    /** 软删除（幂等）。已是删除态返回 true；false 只表示不存在/非本人。 */
    boolean delete(long id);

    /**
     * AI 确认路径专用：创建一条 source=ai、带 confidence 与 replacesId 的记忆。
     * 若 replacesId 非空且属于当前用户 active 记忆，先把旧条置 superseded（同事务由调用方保证）。
     * 内容走敏感词过滤；stable 强制 expiresAt=null，dynamic 未传按 ttl。
     * 手动 CRUD 的 {@link #create} 与此分离，避免"已确认=manual"的语义混淆。
     *
     * @param sessionScope P1.1 会话隔离：null=全局画像；有值=仅该会话生效
     */
    MemoryVO createWithReplaces(String memKey, String content, String memType,
            Double confidence, Long replacesId, Long sourceMessageId, String sessionScope);
}
