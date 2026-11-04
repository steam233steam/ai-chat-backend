package com.aichat.Service.impl;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.aichat.Config.MindButlerProperties;
import com.aichat.Dto.CreateMemoryRequest;
import com.aichat.Dto.MemoryVO;
import com.aichat.Dto.UpdateMemoryRequest;
import com.aichat.Dto.UserDto;
import com.aichat.Entity.UserMemory;
import com.aichat.Exception.BusinessException;
import com.aichat.Mapper.UserMemoryMapper;
import com.aichat.Service.MemoryService;
import com.aichat.utils.MemoryKey;
import com.aichat.utils.SensitiveContentFilter;
import com.aichat.utils.UserHolder;

@Service
public class MemoryServiceimpl implements MemoryService {
    private static final Logger log = LoggerFactory.getLogger(MemoryServiceimpl.class);

    /** 记忆 content 应用层上限（DB 列 512，Java 侧收 256，留足余量） */
    private static final int CONTENT_MAX = 256;

    @Autowired
    private UserMemoryMapper userMemoryMapper;
    @Autowired
    private MindButlerProperties props;
    @Autowired
    private Clock clock;

    @Override
    public List<MemoryVO> listForInjection(int stableLimit, int dynamicLimit) {
        return listForInjection(stableLimit, dynamicLimit, null);
    }

    @Override
    public List<MemoryVO> listForInjection(int stableLimit, int dynamicLimit, String sessionScope) {
        String userId = currentUserId();
        LocalDateTime now = nowLocal();
        int stableLimitCap = Math.max(0, Math.min(stableLimit, 100));
        int dynamicLimitCap = Math.max(0, Math.min(dynamicLimit, 100));

        List<UserMemory> stable = userMemoryMapper.selectActiveByUserAndType(
                userId, "stable", now, sessionScope, stableLimitCap);
        List<UserMemory> dynamic = userMemoryMapper.selectActiveByUserAndType(
                userId, "dynamic", now, sessionScope, dynamicLimitCap);

        // stable 优先；总数受 inject-max 控制（这里由调用方传配额，M1 简化为 stable+dynamic 各 cap）
        List<MemoryVO> result = new ArrayList<>(stableLimitCap + dynamicLimitCap);
        stable.stream().filter(m -> !expired(m, now)).forEach(m -> result.add(toVO(m, now)));
        dynamic.stream().filter(m -> !expired(m, now)).forEach(m -> result.add(toVO(m, now)));
        return result;
    }

    @Override
    public List<MemoryVO> list(int limit) {
        return list(limit, null);
    }

    @Override
    public List<MemoryVO> list(int limit, String sessionScope) {
        String userId = currentUserId();
        int cap = Math.max(1, Math.min(limit, 100));
        LocalDateTime now = nowLocal();
        return userMemoryMapper.selectActiveByUser(userId, now, sessionScope, cap).stream()
                .map(m -> toVO(m, now))
                .toList();
    }

    @Override
    public List<MemoryVO> export() {
        String userId = currentUserId();
        LocalDateTime now = nowLocal();
        return userMemoryMapper.selectExportByUser(userId).stream()
                .map(m -> toVO(m, now))
                .toList();
    }

    @Override
    public MemoryVO get(long id) {
        String userId = currentUserId();
        UserMemory m = userMemoryMapper.selectById(id, userId);
        if (m == null) {
            return null;
        }
        return toVO(m, nowLocal());
    }

    @Override
    public MemoryVO create(CreateMemoryRequest request) {
        String userId = currentUserId();
        LocalDateTime now = nowLocal();
        String memKey = normalizeMemKey(request.getMemKey());
        String memType = normalizeMemType(request.getMemType());
        String content = normalizeContent(request.getContent());
        rejectIfSensitive(content);

        // 同一用户已有完全相同的 active 内容 → 409（v3 8.1）
        if (userMemoryMapper.selectActiveByContent(userId, content, now, null) != null) {
            throw new BusinessException(409, "已存在相同内容的记忆");
        }
        if (userMemoryMapper.countActive(userId, now) >= props.getMemoryMaxPerUser()) {
            throw new BusinessException("记忆数量已达上限（" + props.getMemoryMaxPerUser() + " 条），请先删除旧记忆");
        }

        // 手动创建：source=manual、confidence=1.00（即"已确认"）
        UserMemory m = new UserMemory();
        m.setUserId(userId);
        m.setMemKey(memKey);
        m.setContent(content);
        m.setMemType(memType);
        m.setStatus("active");
        m.setConfidence(new java.math.BigDecimal("1.00"));
        m.setSource("manual");

        // stable 强制不过期；dynamic 未传 expiresAt 则按 ttl 计算
        if ("stable".equals(memType)) {
            if (request.getExpiresAt() != null) {
                throw new BusinessException("stable 记忆不能设置过期时间");
            }
            m.setExpiresAt(null);
        } else {
            m.setExpiresAt(request.getExpiresAt() != null ? request.getExpiresAt() : dynamicExpiry());
        }

        userMemoryMapper.insert(m);
        // 回填 id + version=0
        UserMemory saved = userMemoryMapper.selectById(m.getId(), userId);
        if (saved == null) {
            throw new BusinessException("记忆创建后查询失败，请重试");
        }
        return toVO(saved, nowLocal());
    }

    @Override
    public MemoryVO update(long id, UpdateMemoryRequest request) {
        String userId = currentUserId();
        if (request == null || request.getVersion() == null) {
            throw new BusinessException("version 不能为空");
        }
        UserMemory current = userMemoryMapper.selectById(id, userId);
        if (current == null) {
            return null; // 非本人或已删，上层 404
        }
        LocalDateTime now = nowLocal();
        // 只能改 active 且未过期
        if (!"active".equals(current.getStatus()) || expired(current, now)) {
            throw new BusinessException("该记忆不可修改（已失效）");
        }

        String newContent = (request.getContent() == null || request.getContent().isBlank())
                ? current.getContent() : normalizeContent(request.getContent());
        rejectIfSensitive(newContent);

        String newMemType = (request.getMemType() == null || request.getMemType().isBlank())
                ? current.getMemType() : normalizeMemType(request.getMemType());

        UserMemory upd = new UserMemory();
        upd.setId(id);
        upd.setUserId(userId);
        upd.setContent(newContent);
        upd.setMemType(newMemType);
        upd.setVersion(request.getVersion());

        // expiresAt：stable 置 null；dynamic 可续期（未传则保留原值）
        if ("stable".equals(newMemType)) {
            if (request.getExpiresAt() != null) {
                throw new BusinessException("stable 记忆不能设置过期时间");
            }
            upd.setExpiresAt(null);
        } else {
            // dynamic 未传时：保留原过期时间；stable→dynamic 则按 ttl 重新计算（v3 5.1）
            LocalDateTime expiry = request.getExpiresAt() != null
                    ? request.getExpiresAt() : current.getExpiresAt();
            upd.setExpiresAt(expiry != null ? expiry : dynamicExpiry());
        }

        int rows = userMemoryMapper.updateByIdAndVersion(upd, now);
        if (rows == 0) {
            throw new BusinessException(409, "版本冲突，请刷新后重试");
        }
        return toVO(userMemoryMapper.selectById(id, userId), now);
    }

    @Override
    public boolean delete(long id) {
        String userId = currentUserId();
        int rows = userMemoryMapper.softDelete(id, userId);
        // 幂等：已是 deleted 状态也算成功（v3 8.1）
        return rows > 0 || userMemoryMapper.selectById(id, userId) != null;
    }

    @Override
    public MemoryVO createWithReplaces(String memKey, String content, String memType,
            Double confidence, Long replacesId, Long sourceMessageId, String sessionScope) {
        String userId = currentUserId();
        LocalDateTime now = nowLocal();
        String key = normalizeMemKey(memKey);
        String type = normalizeMemType(memType);
        String cleanContent = normalizeContent(content);
        rejectIfSensitive(cleanContent);

        // 完全相同 active 内容 → 去重，不重复插入，也不替换旧条（v3 6.3）
        // 去重范围与 sessionScope 一致：会话级记忆只在同会话去重，全局记忆跨会话去重
        UserMemory duplicate = userMemoryMapper.selectActiveByContent(userId, cleanContent, now, sessionScope);
        if (duplicate != null) {
            return toVO(duplicate, now);
        }

        // replaces_id 只接受本人 active 且 mem_key/mem_type 一致的旧条；否则忽略替换按新增处理
        Long effectiveReplacesId = validateReplacesId(replacesId, userId, key, type);
        if (effectiveReplacesId != null) {
            int superseded = userMemoryMapper.markSuperseded(effectiveReplacesId, userId);
            if (superseded == 0) {
                effectiveReplacesId = null;
            }
        }
        // 替换会先腾出一条 active 名额；不替换时才需要卡上限
        if (effectiveReplacesId == null
                && userMemoryMapper.countActive(userId, now) >= props.getMemoryMaxPerUser()) {
            throw new BusinessException("记忆数量已达上限（" + props.getMemoryMaxPerUser() + " 条），请先删除旧记忆");
        }

        UserMemory m = new UserMemory();
        m.setUserId(userId);
        m.setSessionId(sessionScope);
        m.setMemKey(key);
        m.setContent(cleanContent);
        m.setMemType(type);
        m.setStatus("active");
        // AI 提取的 confidence 由调用方给（缺省 0.5，夹在 0-1）；缺省 1.00
        double conf = (confidence == null) ? 0.5 : Math.max(0.0, Math.min(1.0, confidence));
        // 不用 String.format：某些 Locale 会输出逗号小数，BigDecimal 解析直接 500
        m.setConfidence(BigDecimal.valueOf(conf).setScale(2, java.math.RoundingMode.HALF_UP));
        m.setSource("ai");
        m.setSourceMessageId(sourceMessageId);
        m.setReplacesId(effectiveReplacesId);

        if ("stable".equals(type)) {
            m.setExpiresAt(null);
        } else {
            m.setExpiresAt(dynamicExpiry());
        }

        userMemoryMapper.insert(m);
        UserMemory saved = userMemoryMapper.selectById(m.getId(), userId);
        if (saved == null) {
            throw new BusinessException("记忆创建后查询失败，请重试");
        }
        return toVO(saved, nowLocal());
    }

    // ---------- 内部辅助 ----------

    private String currentUserId() {
        UserDto user = UserHolder.getUser();
        if (user == null || user.getId() == null) {
            throw new BusinessException(401, "请先登录");
        }
        return user.getId();
    }

    private LocalDateTime nowLocal() {
        return ZonedDateTime.now(clock).withZoneSameInstant(ZoneId.of(props.getTaskZone()))
                .toLocalDateTime();
    }

    private boolean expired(UserMemory m, LocalDateTime now) {
        return m.getExpiresAt() != null && !m.getExpiresAt().isAfter(now);
    }

    private LocalDateTime dynamicExpiry() {
        return nowLocal().plusDays(props.getMemoryDynamicTtlDays());
    }

    private Long validateReplacesId(Long replacesId, String userId, String memKey, String memType) {
        if (replacesId == null) {
            return null;
        }
        UserMemory old = userMemoryMapper.selectById(replacesId, userId);
        if (old == null || !"active".equals(old.getStatus())
                || !memKey.equals(old.getMemKey()) || !memType.equals(old.getMemType())) {
            log.warn("忽略非法 replacesId: userId={}, id={}", userId, replacesId);
            return null;
        }
        return replacesId;
    }

    private String normalizeMemKey(String raw) {
        String key = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (!MemoryKey.isValid(key)) {
            throw new BusinessException("memKey 只能是 style/goal/schedule/relation");
        }
        return key;
    }

    private String normalizeMemType(String memType) {
        if (memType == null || memType.isBlank()) {
            return "stable";
        }
        String t = memType.trim().toLowerCase(Locale.ROOT);
        if (!"stable".equals(t) && !"dynamic".equals(t)) {
            throw new BusinessException("memType 只能是 stable 或 dynamic");
        }
        return t;
    }

    private String normalizeContent(String raw) {
        String c = SensitiveContentFilter.stripControlChars(raw);
        if (c.isEmpty()) {
            throw new BusinessException("记忆内容不能为空");
        }
        int len = c.codePointCount(0, c.length());
        if (len > CONTENT_MAX) {
            throw new BusinessException("记忆内容不能超过 " + CONTENT_MAX + " 字");
        }
        return c;
    }

    private void rejectIfSensitive(String content) {
        String hit = SensitiveContentFilter.detectSensitive(content);
        if (hit != null) {
            log.warn("拒绝记忆入库（{}），userId={}", hit, currentUserId());
            throw new BusinessException(hit + "，不会保存");
        }
    }

    private MemoryVO toVO(UserMemory m, LocalDateTime now) {
        MemoryVO vo = new MemoryVO();
        vo.setId(m.getId());
        vo.setMemKey(m.getMemKey());
        vo.setContent(m.getContent());
        vo.setMemType(m.getMemType());
        vo.setStatus(m.getStatus());
        vo.setConfidence(m.getConfidence() != null ? m.getConfidence().toPlainString() : "1.00");
        vo.setSource(m.getSource());
        vo.setSourceMessageId(m.getSourceMessageId());
        vo.setReplacesId(m.getReplacesId());
        vo.setExpiresAt(m.getExpiresAt());
        vo.setVersion(m.getVersion());
        vo.setCreateTime(m.getCreateTime());
        vo.setUpdateTime(m.getUpdateTime());
        vo.setExpired(m.getExpiresAt() != null && !m.getExpiresAt().isAfter(now));
        return vo;
    }
}
