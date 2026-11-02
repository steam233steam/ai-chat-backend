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

    /** 查当前用户记忆列表（含 active+superseded，排除 deleted；limit 1-100）。 */
    List<MemoryVO> list(int limit);

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

    /** 软删除（幂等）。返回 true 表示本次实际删了，false 表示已是删除态。 */
    boolean delete(long id);
}
