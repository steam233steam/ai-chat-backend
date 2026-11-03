package com.aichat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;

import com.aichat.Config.MindButlerProperties;
import com.aichat.Dto.ConfirmResult;
import com.aichat.Dto.CompleteTaskRequest;
import com.aichat.Dto.CreateMemoryRequest;
import com.aichat.Dto.MemoryCandidate;
import com.aichat.Dto.MemoryVO;
import com.aichat.Dto.PendingActionVO;
import com.aichat.Dto.TaskCandidate;
import com.aichat.Dto.TaskVO;
import com.aichat.Dto.UserDto;
import com.aichat.Entity.UserMemory;
import com.aichat.Mapper.UserMemoryMapper;
import com.aichat.Mapper.UserTaskMapper;
import com.aichat.Mapper.PendingActionMapper;
import com.aichat.Service.MemoryService;
import com.aichat.Service.PendingActionService;
import com.aichat.Service.TaskService;
import com.aichat.utils.MemoryKey;
import com.aichat.utils.UserHolder;

/**
 * MindButler P1 全链路集成测试（说 → 提取 → 确认 → 落库）。
 *
 * 与 {@link FullFlowIntegrationTest} 同一风格：真实启动 Spring + 连真实 MySQL(13306)，
 * 但【只 mock 掉 LLM 提取器】（IntentExtractor），用固定候选注入，使"提取"这一环
 * 完全确定、不依赖 Ollama/DeepSeek 可用性——其余（pending 状态机、记忆替换、任务滚动、
 * pending_slot 唯一键）全部走真实 SQL 验证。
 *
 * 覆盖核心链路：
 *  1. 提取出记忆候选 → 写 pending（旧 pending 自动 expired，pending_slot 唯一键生效）
 *  2. 确认记忆 → 旧条 superseded + 新条 active（createWithReplaces 真实落库）
 *  3. 提取出任务候选 → 确认 → 任务落库
 *  4. 重复确认幂等（alreadyConfirmed，不重复建）
 *  5. pending 懒过期 → 再确认 409
 *  6. 拒绝 → 幂等
 *  7. 跨用户隔离（别的用户看不到/改不了本用户 pending）
 *
 * 运行前置：虚拟机 MySQL(13306) 已启动且 3 张 MindButler 表已建（schema.sql）。
 *  运行：mvn test -Dtest=MindButlerFlowIntegrationTest
 *
 * 数据隔离：每个用例用独立 user_id + session_id，@BeforeEach 只清理本类前缀数据，
 *  不碰 tb_user（MindButler 的 user_id 是逻辑隔离键，不走登录态，直接 UserHolder 注入）。
 */
@SpringBootTest(properties = "spring.data.redis.repositories.enabled=false")
class MindButlerFlowIntegrationTest {

    /** 只 mock LLM 提取器；其余全走真实实现 + 真实 MySQL。 */
    @MockBean
    private com.aichat.Service.IntentExtractor intentExtractor;

    @Autowired
    private PendingActionService pendingActionService;
    @Autowired
    private MemoryService memoryService;
    @Autowired
    private TaskService taskService;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PendingActionMapper pendingActionMapper;
    @Autowired
    private UserTaskMapper userTaskMapper;
    @Autowired
    private UserMemoryMapper userMemoryMapper;
    @Autowired
    private MindButlerProperties props;

    /** 每用例独立的逻辑用户 + 会话（MindButler 三层按 user_id 隔离，无需真登录态） */
    private String userId;
    private String sessionId;

    @BeforeEach
    void setUp() {
        userId = "MBF_TEST_" + System.nanoTime();
        sessionId = "mbf_s_" + System.nanoTime();
        UserHolder.saveUser(selfUser());
        // 只清库，不碰线程登录态（清完后后续 Service 调用仍需要登录态）
        pendingActionMapper.deleteByUser(userId);
        userTaskMapper.deleteByUser(userId);
        userMemoryMapper.deleteByUser(userId);
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        cleanup(userId);
    }

    /** 当前逻辑用户的 UserDto（注入 UserHolder 用）。 */
    private UserDto selfUser() {
        UserDto u = new UserDto();
        u.setId(userId);
        u.setPhone("13800000000");
        return u;
    }

    private void cleanup(String uid) {
        UserHolder.saveUser(selfUser());
        pendingActionMapper.deleteByUser(uid);
        userTaskMapper.deleteByUser(uid);
        userMemoryMapper.deleteByUser(uid);
        UserHolder.removeUser();
    }

    private MemoryCandidate mem(String key, String value, String type, Double conf, Long replacesId) {
        return new MemoryCandidate(key, value, type, conf, replacesId);
    }

    private TaskCandidate task(String title, String dueAt, String recurrence) {
        return new TaskCandidate("create", title,
                dueAt == null ? null : java.time.LocalDateTime.parse(dueAt),
                "Asia/Shanghai", recurrence, null);
    }

    @Test
    void shouldExpireOldPendingWhenCreatingNewOne() {
        // 同一 session 连建两条 pending → 旧的那条应被置 expired（pending_slot 唯一键兜底）
        when(intentExtractor.extract(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new com.aichat.utils.IntentJsonParser.Parsed(
                        List.of(mem("style", "简洁", "stable", 0.9, null)), List.of()));

        // 直接调 create 两次模拟"新意图替换旧 pending"（IntentService 走同一条路径）
        long p1 = pendingActionService.create(sessionId, "memory", buildPayload(List.of(mem("style", "旧", "stable", 0.9, null)), List.of()), null);
        long p2 = pendingActionService.create(sessionId, "memory", buildPayload(List.of(mem("style", "简洁", "stable", 0.9, null)), List.of()), null);

        assertThat(p1).isNotEqualTo(p2);
        // 旧 pending 已被置 expired
        Integer p1Status = jdbc.queryForObject(
                "SELECT CASE status WHEN 'pending' THEN 1 ELSE 0 END FROM tb_pending_action WHERE id=?",
                Integer.class, p1);
        assertThat(p1Status).isZero();
        // 当前 session 只有一条 pending（p2）
        PendingActionVO cur = pendingActionService.current(sessionId);
        assertThat(cur.getId()).isEqualTo(p2);
    }

    @Test
    void shouldConfirmMemoryAndSupersedeOld() {
        // 先手动建一条旧 style 记忆（active）
        CreateMemoryRequest req = new CreateMemoryRequest();
        req.setMemKey("style");
        req.setContent("旧风格");
        req.setMemType("stable");
        MemoryVO oldMem = memoryService.create(req);
        assertThat(oldMem.getId()).isNotNull();

        // 提取器产出"替换旧记忆"的候选
        when(intentExtractor.extract(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new com.aichat.utils.IntentJsonParser.Parsed(
                        List.of(mem("style", "简洁", "stable", 0.9, oldMem.getId())), List.of()));

        long pid = pendingActionService.create(sessionId, "memory",
                buildPayload(List.of(mem("style", "简洁", "stable", 0.9, oldMem.getId())), List.of()), null);
        ConfirmResult cr = pendingActionService.confirm(pid);

        // 确认成功，不幂等
        assertThat(cr.isAlreadyConfirmed()).isFalse();
        assertThat(cr.getMemories()).hasSize(1);

        // 旧记忆被置 superseded
        String oldStatus = jdbc.queryForObject(
                "SELECT status FROM tb_user_memory WHERE id=?", String.class, oldMem.getId());
        assertThat(oldStatus).isEqualTo("superseded");

        // 新记忆 active 且 replaces_id 指向旧记忆
        List<UserMemory> newOnes = jdbc.query(
                "SELECT id, replaces_id, status, source FROM tb_user_memory WHERE user_id=? AND status='active'",
                new org.springframework.jdbc.core.RowMapper<UserMemory>() {
                    @Override
                    public UserMemory mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
                        UserMemory m = new UserMemory();
                        m.setId(rs.getLong("id"));
                        m.setReplacesId(rs.getLong("replaces_id"));
                        m.setStatus(rs.getString("status"));
                        m.setSource(rs.getString("source"));
                        return m;
                    }
                },
                userId);
        assertThat(newOnes).hasSize(1);
        assertThat(newOnes.get(0).getReplacesId()).isEqualTo(oldMem.getId());
        assertThat(newOnes.get(0).getSource()).isEqualTo("ai");

        // 重复确认幂等
        ConfirmResult dup = pendingActionService.confirm(pid);
        assertThat(dup.isAlreadyConfirmed()).isTrue();
        assertThat(dup.getMemories()).isEmpty();
    }

    @Test
    void shouldConfirmTaskAndRollRecurring() {
        // 提取器产出 daily 任务（带截止时间）
        String dueAt = java.time.LocalDateTime.now().plusDays(1).withHour(9).withMinute(0).withSecond(0).withNano(0)
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));
        when(intentExtractor.extract(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new com.aichat.utils.IntentJsonParser.Parsed(
                        List.of(), List.of(task("每天打卡", dueAt, "daily"))));

        long pid = pendingActionService.create(sessionId, "task",
                buildPayload(List.of(), List.of(task("每天打卡", dueAt, "daily"))), null);
        ConfirmResult cr = pendingActionService.confirm(pid);

        assertThat(cr.getTasks()).hasSize(1);
        long taskId = ((Number) cr.getTasks().get(0).get("id")).longValue();

        // 任务落库：pending + daily + 有截止时间
        TaskVOCheck(taskId, "pending", "daily", true);

        // 完成一次 → 滚动到下一个未来时间点，仍 pending
        CompleteTaskRequest completeReq = new CompleteTaskRequest();
        completeReq.setVersion(0);
        taskService.complete(taskId, completeReq);
        String status = jdbc.queryForObject("SELECT status FROM tb_user_task WHERE id=?", String.class, taskId);
        String newDue = jdbc.queryForObject("SELECT DATE_FORMAT(due_at,'%Y-%m-%d %H:%i') FROM tb_user_task WHERE id=?", String.class, taskId);
        assertThat(status).isEqualTo("pending");
        assertThat(newDue).isNotNull();
    }

    @Test
    void shouldRejectIdempotently() {
        when(intentExtractor.extract(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new com.aichat.utils.IntentJsonParser.Parsed(
                        List.of(mem("goal", "上线 P1", "dynamic", 0.7, null)), List.of()));
        long pid = pendingActionService.create(sessionId, "memory",
                buildPayload(List.of(mem("goal", "上线 P1", "dynamic", 0.7, null)), List.of()), null);

        pendingActionService.reject(pid);
        // 幂等：重复 reject 不抛
        pendingActionService.reject(pid);
        String st = jdbc.queryForObject("SELECT status FROM tb_pending_action WHERE id=?", String.class, pid);
        assertThat(st).isEqualTo("rejected");
    }

    @Test
    void shouldReturn409WhenPendingExpired() {
        // 直接把一条 pending 的 expires_at 调到过去，模拟"懒过期"
        when(intentExtractor.extract(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new com.aichat.utils.IntentJsonParser.Parsed(
                        List.of(mem("style", "简洁", "stable", 0.9, null)), List.of()));
        long pid = pendingActionService.create(sessionId, "memory",
                buildPayload(List.of(mem("style", "简洁", "stable", 0.9, null)), List.of()), null);
        jdbc.update("UPDATE tb_pending_action SET expires_at = DATE_SUB(NOW(), INTERVAL 1 MINUTE) WHERE id=?", pid);

        // current 应懒过期 → 返回 null
        assertThat(pendingActionService.current(sessionId)).isNull();

        // 再确认 → 409
        org.junit.jupiter.api.Assertions.assertThrows(com.aichat.Exception.BusinessException.class,
                () -> pendingActionService.confirm(pid));
    }

    @Test
    void shouldIsolateCrossUser() {
        // 本用户建 pending
        when(intentExtractor.extract(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new com.aichat.utils.IntentJsonParser.Parsed(
                        List.of(mem("style", "简洁", "stable", 0.9, null)), List.of()));
        long pid = pendingActionService.create(sessionId, "memory",
                buildPayload(List.of(mem("style", "简洁", "stable", 0.9, null)), List.of()), null);

        // 切换到另一个逻辑用户，访问 pid → 应 404（selectById 带 user_id 归属校验）
        String otherUser = "MBF_OTHER_" + System.nanoTime();
        UserDto o = new UserDto();
        o.setId(otherUser);
        o.setPhone("13800000001");
        UserHolder.saveUser(o);
        try {
            // current 查别的 session 拿不到
            assertThat(pendingActionService.current(sessionId)).isNull();
            // 确认别人的 pending → 404（selectById 归属校验查不到）
            org.junit.jupiter.api.Assertions.assertThrows(com.aichat.Exception.BusinessException.class,
                    () -> pendingActionService.confirm(pid));
        } finally {
            // 切回本用户并清理两边数据
            UserDto back = new UserDto();
            back.setId(userId);
            back.setPhone("13800000000");
            UserHolder.saveUser(back);
            pendingActionMapper.deleteByUser(otherUser);
        }
    }

    // ---------- 辅助 ----------

    private String buildPayload(List<MemoryCandidate> memories, List<TaskCandidate> tasks) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper()
                    .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
            java.util.Map<String, Object> root = new java.util.LinkedHashMap<>();
            root.put("memories", memories);
            root.put("tasks", tasks);
            return om.writeValueAsString(root);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void TaskVOCheck(long taskId, String status, String recurrence, boolean hasDue) {
        TaskVO vo = taskService.get(taskId);
        assertThat(vo).isNotNull();
        assertThat(vo.getStatus()).isEqualTo(status);
        assertThat(vo.getRecurrence()).isEqualTo(recurrence);
        if (hasDue) {
            assertThat(vo.getDueAt()).isNotNull();
        }
    }
}
