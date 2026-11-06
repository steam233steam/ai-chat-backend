package com.aichat.Service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.aichat.Config.MindButlerProperties;
import com.aichat.Dto.CreateMemoryRequest;
import com.aichat.Dto.MemoryVO;
import com.aichat.Dto.UpdateMemoryRequest;
import com.aichat.Dto.UserDto;
import com.aichat.Entity.UserMemory;
import com.aichat.Exception.BusinessException;
import com.aichat.Mapper.UserMemoryMapper;
import com.aichat.utils.MemoryKey;
import com.aichat.utils.SensitiveContentFilter;
import com.aichat.utils.UserHolder;

/**
 * MemoryService 单测：纯 Mockito（不连 MySQL），反射注入 Mapper/Config/Clock，
 * UserHolder 反射模拟登录态。覆盖 CRUD + 过期过滤 + 跨用户 404 + 乐观锁 409 + 敏感词拒绝。
 */
class MemoryServiceImplTest {

    private UserMemoryMapper mapper;
    private MindButlerProperties props;
    private MemoryServiceImpl service;

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 2, 22, 0);

    @BeforeEach
    void setUp() {
        mapper = mock(UserMemoryMapper.class);
        props = new MindButlerProperties();
        service = new MemoryServiceImpl(mapper, props, null);
        ReflectionTestUtils.setField(service, "clock",
                Clock.fixed(java.time.Instant.now(), SHANGHAI));
        login("USER_ID_1234567890");
    }

    private void login(String uid) {
        UserDto u = new UserDto();
        u.setId(uid);
        UserHolder.saveUser(u);
    }

    // ---------- 创建 ----------

    @Test
    void shouldCreateManualStableMemory() {
        CreateMemoryRequest req = new CreateMemoryRequest();
        req.setMemKey("style");
        req.setContent("简洁");
        req.setMemType("stable");
        // insert 后 service 用 m.getId() 回查；模拟 MyBatis 回填自增 id + 查到该条
        when(mapper.insert(any(UserMemory.class))).thenAnswer(inv -> {
            UserMemory m = inv.getArgument(0);
            m.setId(42L); // 模拟自增回填
            return 1;
        });
        when(mapper.selectById(eq(42L), eq("USER_ID_1234567890")))
                .thenReturn(mem(42L, "style", "简洁", "stable", "active", null, NOW));

        MemoryVO vo = service.create(req);

        assertThat(vo.getStatus()).isEqualTo("active");
        assertThat(vo.getSource()).isEqualTo("manual");
        assertThat(vo.getConfidence()).isEqualTo("1.00");
        assertThat(vo.getExpiresAt()).isNull();
        verify(mapper).insert(any(UserMemory.class));
    }

    @Test
    void shouldRejectStableWithExpiresAt() {
        CreateMemoryRequest req = new CreateMemoryRequest();
        req.setMemKey("style");
        req.setContent("简洁");
        req.setMemType("stable");
        req.setExpiresAt(NOW.plusDays(30));
        assertThatThrownBy(() -> service.create(req)).isInstanceOf(BusinessException.class);
        verify(mapper, never()).insert(any());
    }

    @Test
    void shouldRejectSensitiveContent() {
        CreateMemoryRequest req = new CreateMemoryRequest();
        req.setMemKey("relation");
        req.setContent("我银行卡号 6222 0200 0000 0000");
        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("银行卡");
        verify(mapper, never()).insert(any());
    }

    @Test
    void shouldRejectBlankContent() {
        CreateMemoryRequest req = new CreateMemoryRequest();
        req.setMemKey("style");
        req.setContent("   ");
        // 空白 content 被 normalizeContent 拒（长度校验在 @Size，service 层 catch 空内容）
        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能为空");
        verify(mapper, never()).insert(any());
    }

    @Test
    void shouldCreateWithValidMemKey() {
        // P1 手动 CRUD 的 memKey 走 DB VARCHAR(32)，合法 4 维度都允许
        for (String key : java.util.Arrays.stream(MemoryKey.values()).map(MemoryKey::getValue).toList()) {
            CreateMemoryRequest req = new CreateMemoryRequest();
            req.setMemKey(key);
            req.setContent("ok-" + key);
            req.setMemType("stable");
            when(mapper.insert(any(UserMemory.class))).thenAnswer(inv -> {
                UserMemory m = inv.getArgument(0);
                m.setId(1L);
                return 1;
            });
            when(mapper.selectById(eq(1L), eq("USER_ID_1234567890")))
                    .thenAnswer(inv -> mem(1L, key, "ok-" + key, "stable", "active", null, NOW));

            MemoryVO vo = service.create(req);
            assertThat(vo.getMemKey()).isEqualTo(key);
        }
    }

    @Test
    void shouldRejectUnknownMemKeyOnManualCreate() {
        CreateMemoryRequest req = new CreateMemoryRequest();
        req.setMemKey("tech");
        req.setContent("用 Java");
        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("memKey");
        verify(mapper, never()).insert(any());
    }

    @Test
    void shouldRejectDuplicateActiveContent() {
        when(mapper.selectActiveByContent(eq("USER_ID_1234567890"), eq("简洁"), any(LocalDateTime.class), eq(null)))
                .thenReturn(mem(9L, "style", "简洁", "stable", "active", null, NOW));
        CreateMemoryRequest req = new CreateMemoryRequest();
        req.setMemKey("style");
        req.setContent("简洁");
        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("相同内容");
        verify(mapper, never()).insert(any());
    }

    @Test
    void shouldRejectWhenActiveMemoryLimitReached() {
        when(mapper.countActive(eq("USER_ID_1234567890"), any(LocalDateTime.class))).thenReturn(100);
        CreateMemoryRequest req = new CreateMemoryRequest();
        req.setMemKey("style");
        req.setContent("第 101 条");
        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("上限");
    }

    @Test
    void shouldIgnoreReplacesIdWithDifferentKey() {
        when(mapper.selectById(99L, "USER_ID_1234567890"))
                .thenReturn(mem(99L, "style", "旧风格", "stable", "active", null, NOW));
        when(mapper.selectActiveByContent(eq("USER_ID_1234567890"), eq("新目标"), any(LocalDateTime.class), eq(null)))
                .thenReturn(null);
        when(mapper.countActive(eq("USER_ID_1234567890"), any(LocalDateTime.class))).thenReturn(0);
        when(mapper.insert(any(UserMemory.class))).thenAnswer(inv -> {
            UserMemory m = inv.getArgument(0);
            m.setId(42L);
            return 1;
        });
        when(mapper.selectById(42L, "USER_ID_1234567890"))
                .thenReturn(mem(42L, "goal", "新目标", "stable", "active", null, NOW));

        service.createWithReplaces("goal", "新目标", "stable", 0.9, 99L, 7L, null);

        // key 不一致 → 不替换旧条，按新增处理且 replacesId 为空
        verify(mapper, never()).markSuperseded(anyLong(), anyString());
        ArgumentCaptor<UserMemory> captor = ArgumentCaptor.forClass(UserMemory.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getReplacesId()).isNull();
        assertThat(captor.getValue().getSessionId()).isNull();
    }

    @Test
    void shouldCreateSessionScopedMemoryWhenSessionScopeGiven() {
        // P1.1：scope=session 的候选确认时落 session_id，注入查询时只取全局+本会话
        when(mapper.selectActiveByContent(eq("USER_ID_1234567890"), eq("猫娘"), any(LocalDateTime.class), eq("s-1")))
                .thenReturn(null);
        when(mapper.countActive(eq("USER_ID_1234567890"), any(LocalDateTime.class))).thenReturn(0);
        when(mapper.insert(any(UserMemory.class))).thenAnswer(inv -> {
            UserMemory m = inv.getArgument(0);
            m.setId(43L);
            return 1;
        });
        when(mapper.selectById(43L, "USER_ID_1234567890"))
                .thenReturn(mem(43L, "style", "猫娘", "stable", "active", null, NOW));

        service.createWithReplaces("style", "猫娘", "stable", 0.9, null, 7L, "s-1");

        ArgumentCaptor<UserMemory> captor = ArgumentCaptor.forClass(UserMemory.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getSessionId()).isEqualTo("s-1");
    }

    @Test
    void shouldDedupeIdenticalActiveContentFromAi() {
        when(mapper.selectActiveByContent(eq("USER_ID_1234567890"), eq("简洁"), any(LocalDateTime.class), eq(null)))
                .thenReturn(mem(7L, "style", "简洁", "stable", "active", null, NOW));

        MemoryVO vo = service.createWithReplaces("style", "简洁", "stable", 0.9, null, 7L, null);

        assertThat(vo.getId()).isEqualTo(7L);
        verify(mapper, never()).insert(any());
    }

    @Test
    void shouldListActiveWithLimit() {
        when(mapper.selectActiveByUser(eq("USER_ID_1234567890"), any(LocalDateTime.class), eq(null), eq(1)))
                .thenReturn(List.of(mem(1L, "style", "简洁", "stable", "active", null, NOW)));

        List<MemoryVO> vos = service.list(1);

        assertThat(vos).hasSize(1);
        verify(mapper).selectActiveByUser(eq("USER_ID_1234567890"), any(LocalDateTime.class), eq(null), eq(1));
    }

    // ---------- 查询 ----------

    @Test
    void shouldListActiveOnlyForInjection() {
        when(mapper.selectActiveByUserAndType(eq("USER_ID_1234567890"), eq("stable"), any(LocalDateTime.class), eq(null), anyInt()))
                .thenReturn(List.of(mem(1L, "style", "简洁", "stable", "active", null, NOW)));
        when(mapper.selectActiveByUserAndType(eq("USER_ID_1234567890"), eq("dynamic"), any(LocalDateTime.class), eq(null), anyInt()))
                .thenReturn(List.of(mem(2L, "goal", "备考", "dynamic", "active", NOW.plusDays(10), NOW)));

        List<MemoryVO> vos = service.listForInjection(5, 5);

        assertThat(vos).hasSize(2);
        assertThat(vos.get(0).getMemKey()).isEqualTo("style");
        assertThat(vos.get(1).getMemKey()).isEqualTo("goal");
        // 过期过滤：stable 无 expires → 不过期
        assertThat(vos.get(0).getExpired()).isFalse();
    }

    @Test
    void shouldFilterExpiredDynamicFromInjection() {
        when(mapper.selectActiveByUserAndType(eq("USER_ID_1234567890"), eq("stable"), any(LocalDateTime.class), eq(null), anyInt()))
                .thenReturn(List.of());
        when(mapper.selectActiveByUserAndType(eq("USER_ID_1234567890"), eq("dynamic"), any(LocalDateTime.class), eq(null), anyInt()))
                .thenReturn(List.of(mem(3L, "goal", "过期了", "dynamic", "active", NOW.minusDays(1), NOW)));

        List<MemoryVO> vos = service.listForInjection(5, 5);

        assertThat(vos).isEmpty(); // 过期 dynamic 被过滤
    }

    @Test
    void shouldIsolateOtherSessionMemoriesFromInjection() {
        // P1.1：sessionScope="s-1" 时，mapper 被传入 "s-1"（SQL 里 WHERE session_id IS NULL OR session_id='s-1'）
        when(mapper.selectActiveByUserAndType(eq("USER_ID_1234567890"), eq("stable"),
                any(LocalDateTime.class), eq("s-1"), anyInt()))
                .thenReturn(List.of(mem(1L, "style", "简洁", "stable", "active", null, NOW)));
        when(mapper.selectActiveByUserAndType(eq("USER_ID_1234567890"), eq("dynamic"),
                any(LocalDateTime.class), eq("s-1"), anyInt()))
                .thenReturn(List.of());

        List<MemoryVO> vos = service.listForInjection(5, 5, "s-1");

        assertThat(vos).hasSize(1);
        // 关键断言：mapper 拿到的 sessionScope 是 "s-1"，别的会话的记忆不会进注入
        verify(mapper).selectActiveByUserAndType(eq("USER_ID_1234567890"), eq("stable"),
                any(LocalDateTime.class), eq("s-1"), anyInt());
        verify(mapper).selectActiveByUserAndType(eq("USER_ID_1234567890"), eq("dynamic"),
                any(LocalDateTime.class), eq("s-1"), anyInt());
    }

    @Test
    void shouldReturnNullOnCrossUserGet() {
        when(mapper.selectById(99L, "USER_ID_1234567890")).thenReturn(null);
        assertThat(service.get(99L)).isNull();
    }

    // ---------- 更新（乐观锁） ----------

    @Test
    void shouldUpdateWithMatchingVersion() {
        when(mapper.selectById(1L, "USER_ID_1234567890"))
                .thenReturn(mem(1L, "style", "简洁", "stable", "active", null, NOW));
        when(mapper.updateByIdAndVersion(any(UserMemory.class), any(LocalDateTime.class))).thenReturn(1);

        UpdateMemoryRequest req = new UpdateMemoryRequest();
        req.setContent("非常简洁");
        req.setVersion(0);

        MemoryVO vo = service.update(1L, req);

        assertThat(vo).isNotNull();
        verify(mapper).updateByIdAndVersion(any(), any(LocalDateTime.class));
    }

    @Test
    void shouldSetDynamicExpiryWhenChangingStableToDynamic() {
        when(mapper.selectById(1L, "USER_ID_1234567890"))
                .thenReturn(mem(1L, "style", "简洁", "stable", "active", null, NOW));
        when(mapper.updateByIdAndVersion(any(UserMemory.class), any(LocalDateTime.class))).thenReturn(1);

        UpdateMemoryRequest req = new UpdateMemoryRequest();
        req.setMemType("dynamic");
        req.setVersion(0);

        service.update(1L, req);

        org.mockito.ArgumentCaptor<UserMemory> captor =
                org.mockito.ArgumentCaptor.forClass(UserMemory.class);
        verify(mapper).updateByIdAndVersion(captor.capture(), any(LocalDateTime.class));
        assertThat(captor.getValue().getExpiresAt()).isAfter(NOW);
    }

    @Test
    void shouldReturn409OnVersionConflict() {
        when(mapper.selectById(1L, "USER_ID_1234567890"))
                .thenReturn(mem(1L, "style", "简洁", "stable", "active", null, NOW));
        when(mapper.updateByIdAndVersion(any(UserMemory.class), any(LocalDateTime.class))).thenReturn(0);

        UpdateMemoryRequest req = new UpdateMemoryRequest();
        req.setContent("x");
        req.setVersion(0);

        assertThatThrownBy(() -> service.update(1L, req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("版本冲突");
    }

    @Test
    void shouldReturnNullWhenUpdatingDeletedMemory() {
        when(mapper.selectById(1L, "USER_ID_1234567890"))
                .thenReturn(mem(1L, "style", "简洁", "stable", "deleted", null, NOW));
        UpdateMemoryRequest req = new UpdateMemoryRequest();
        req.setVersion(0);
        assertThatThrownBy(() -> service.update(1L, req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不可修改");
    }

    // ---------- 删除（幂等） ----------

    @Test
    void shouldSoftDeleteIdempotently() {
        when(mapper.softDelete(1L, "USER_ID_1234567890")).thenReturn(1);
        when(mapper.softDelete(2L, "USER_ID_1234567890")).thenReturn(0);

        assertThat(service.delete(1L)).isTrue();
        assertThat(service.delete(2L)).isFalse(); // 已删 → false（上层 404）
    }

    // ---------- 工具 ----------

    private UserMemory mem(long id, String key, String content, String type, String status,
            LocalDateTime expiresAt, LocalDateTime updateTime) {
        UserMemory m = new UserMemory();
        m.setId(id);
        m.setUserId("USER_ID_1234567890");
        m.setMemKey(key);
        m.setContent(content);
        m.setMemType(type);
        m.setStatus(status);
        m.setConfidence(new java.math.BigDecimal("1.00"));
        m.setSource("manual");
        m.setExpiresAt(expiresAt);
        m.setVersion(0);
        m.setCreateTime(updateTime);
        m.setUpdateTime(updateTime);
        return m;
    }

    private UserMemory memory(Long id) {
        return mem(id, "style", "简洁", "stable", "active", null, NOW);
    }

    @Test
    void shouldExposeSessionIdInMemoryVO() {
        when(mapper.selectActiveByContent(eq("USER_ID_1234567890"), eq("猫娘"),
                any(LocalDateTime.class), eq("s-1"))).thenReturn(null);
        when(mapper.countActive(eq("USER_ID_1234567890"), any(LocalDateTime.class))).thenReturn(0);
        when(mapper.insert(any(UserMemory.class))).thenAnswer(inv -> {
            UserMemory m = inv.getArgument(0);
            m.setId(43L);
            return 1;
        });
        UserMemory saved = mem(43L, "style", "猫娘", "stable", "active", null, NOW);
        saved.setSessionId("s-1");
        when(mapper.selectById(43L, "USER_ID_1234567890")).thenReturn(saved);

        MemoryVO vo = service.createWithReplaces("style", "猫娘", "stable", 0.9, null, 7L, "s-1");

        assertThat(vo.getSessionId()).isEqualTo("s-1");
    }

    @Test
    void shouldIgnoreReplacesIdFromAnotherSessionScope() {
        UserMemory old = mem(99L, "style", "旧风格", "stable", "active", null, NOW);
        old.setSessionId("s-1");
        when(mapper.selectById(99L, "USER_ID_1234567890")).thenReturn(old);
        when(mapper.selectActiveByContent(eq("USER_ID_1234567890"), eq("新风格"),
                any(LocalDateTime.class), eq(null))).thenReturn(null);
        when(mapper.countActive(eq("USER_ID_1234567890"), any(LocalDateTime.class))).thenReturn(0);
        when(mapper.insert(any(UserMemory.class))).thenAnswer(inv -> {
            UserMemory m = inv.getArgument(0);
            m.setId(42L);
            return 1;
        });
        when(mapper.selectById(42L, "USER_ID_1234567890"))
                .thenReturn(mem(42L, "style", "新风格", "stable", "active", null, NOW));

        service.createWithReplaces("style", "新风格", "stable", 0.9, 99L, 7L, null);

        verify(mapper, never()).markSuperseded(anyLong(), anyString());
        ArgumentCaptor<UserMemory> captor = ArgumentCaptor.forClass(UserMemory.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getReplacesId()).isNull();
    }

    @Test
    void shouldTreatAlreadyDeletedMemoryAsIdempotentSuccess() {
        when(mapper.softDelete(1L, "USER_ID_1234567890")).thenReturn(0);
        UserMemory deleted = mem(1L, "style", "简洁", "stable", "deleted", null, NOW);
        when(mapper.selectById(1L, "USER_ID_1234567890")).thenReturn(deleted);

        assertThat(service.delete(1L)).isTrue();
        verify(mapper).selectById(1L, "USER_ID_1234567890");
    }

    @Test
    void shouldReturnNullWhenGettingDeletedMemory() {
        UserMemory deleted = mem(1L, "style", "简洁", "stable", "deleted", null, NOW);
        when(mapper.selectById(1L, "USER_ID_1234567890")).thenReturn(deleted);

        assertThat(service.get(1L)).isNull();
    }

}
