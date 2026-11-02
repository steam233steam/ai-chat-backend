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
        String userId = currentUserId();
        LocalDateTime now = nowLocal();
        int stableLimitCap = Math.max(0, Math.min(stableLimit, 100));
        int dynamicLimitCap = Math.max(0, Math.min(dynamicLimit, 100));

        List<UserMemory> stable = userMemoryMapper.selectActiveByUserAndType(userId, "stable", stableLimitCap);
        List<UserMemory> dynamic = userMemoryMapper.selectActiveByUserAndType(userId, "dynamic", dynamicLimitCap);

        // stable 优先；总数受 inject-max 控制（这里由调用方传配额，M1 简化为 stable+dynamic 各 cap）
        List<MemoryVO> result = new ArrayList<>(stableLimitCap + dynamicLimitCap);
        stable.stream().filter(m -> !expired(m, now)).forEach(m -> result.add(toVO(m, now)));
        dynamic.stream().filter(m -> !expired(m, now)).forEach(m -> result.add(toVO(m, now)));
        return result;
    }

    @Override
    public List<MemoryVO> list(int limit) {
        String userId = currentUserId();
        int cap = Math.max(1, Math.min(limit, 100));
        LocalDateTime now = nowLocal();
        return userMemoryMapper.selectExportByUser(userId, cap).stream()
                .map(m -> toVO(m, now))
                .toList();
    }

    @Override
    public List<MemoryVO> export() {
        String userId = currentUserId();
        // 导出不限条数（max-per-user 兜底）
        int cap = props.getMemoryMaxPerUser();
        LocalDateTime now = nowLocal();
        return userMemoryMapper.selectExportByUser(userId, cap).stream()
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
        String memType = normalizeMemType(request.getMemType());
        String content = normalizeContent(request.getContent());
        rejectIfSensitive(content);

        // 手动创建：source=manual、confidence=1.00（即"已确认"）
        UserMemory m = new UserMemory();
        m.setUserId(userId);
        m.setMemKey(request.getMemKey());
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
        // 只能改 active 且未过期
        if (!"active".equals(current.getStatus()) || expired(current, nowLocal())) {
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
            upd.setExpiresAt(request.getExpiresAt() != null ? request.getExpiresAt() : current.getExpiresAt());
        }

        int rows = userMemoryMapper.updateByIdAndVersion(upd);
        if (rows == 0) {
            throw new BusinessException(409, "版本冲突，请刷新后重试");
        }
        return toVO(userMemoryMapper.selectById(id, userId), nowLocal());
    }

    @Override
    public boolean delete(long id) {
        String userId = currentUserId();
        int rows = userMemoryMapper.softDelete(id, userId);
        return rows > 0;
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
