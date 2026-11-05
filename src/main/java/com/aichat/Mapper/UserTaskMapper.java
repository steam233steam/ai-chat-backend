package com.aichat.Mapper;

import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import com.aichat.Entity.UserTask;

@Mapper
public interface UserTaskMapper {

    // 插入一条任务，自增 id 回填
    @Insert("INSERT INTO tb_user_task(user_id, title, due_at, timezone, recurrence, recurrence_rule, " +
            "status, raw_input, source_message_id, created_from_session, version) " +
            "VALUES(#{userId}, #{title}, #{dueAt}, #{timezone}, #{recurrence}, #{recurrenceRule}, " +
            "'pending', #{rawInput}, #{sourceMessageId}, #{createdFromSession}, 0)")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(UserTask task);

    // 查当前用户某条任务（带归属校验，跨用户查不到 → 上层 404）
    @Select("SELECT id, user_id, title, due_at, timezone, recurrence, recurrence_rule, status, " +
            "deleted_at, last_notified_at, completed_at, raw_input, source_message_id, " +
            "created_from_session, version, " +
            "create_time, update_time " +
            "FROM tb_user_task WHERE id = #{id} AND user_id = #{userId} AND deleted_at IS NULL")
    UserTask selectById(@Param("id") long id, @Param("userId") String userId);

    // 任务列表：默认 pending，排除软删；游标分页 beforeId（id < beforeId，倒序取 limit，调用方反转）
    // P1.5：任务栏挂聊天界面侧边，溯源 created_from_session，但可见性仍全账号（不按 session 过滤）
    @Select("SELECT id, user_id, title, due_at, timezone, recurrence, recurrence_rule, status, " +
            "deleted_at, last_notified_at, completed_at, raw_input, source_message_id, " +
            "created_from_session, version, " +
            "create_time, update_time " +
            "FROM tb_user_task " +
            "WHERE user_id = #{userId} AND deleted_at IS NULL AND status = #{status} AND id < #{beforeId} " +
            "ORDER BY id DESC LIMIT #{limit}")
    List<UserTask> selectByUserBeforeId(@Param("userId") String userId,
            @Param("status") String status,
            @Param("beforeId") long beforeId,
            @Param("limit") int limit);

    // 到期待办：status=pending、未软删、有截止时间且已到期；P1 只读不回写 last_notified_at
    @Select("SELECT id, user_id, title, due_at, timezone, recurrence, recurrence_rule, status, " +
            "deleted_at, last_notified_at, completed_at, raw_input, source_message_id, " +
            "created_from_session, version, " +
            "create_time, update_time " +
            "FROM tb_user_task " +
            "WHERE user_id = #{userId} AND status = 'pending' AND deleted_at IS NULL " +
            "AND due_at IS NOT NULL AND due_at <= #{now} " +
            "ORDER BY due_at ASC LIMIT #{limit}")
    List<UserTask> selectDue(@Param("userId") String userId,
            @Param("now") java.time.LocalDateTime now,
            @Param("limit") int limit);

    // 含软删的存在性判断（DELETE 幂等用）
    @Select("SELECT COUNT(*) FROM tb_user_task WHERE id = #{id} AND user_id = #{userId}")
    int countById(@Param("id") long id, @Param("userId") String userId);

    // 当前用户未软删任务数（每用户上限兜底）
    @Select("SELECT COUNT(*) FROM tb_user_task WHERE user_id = #{userId} AND deleted_at IS NULL")
    int countByUser(@Param("userId") String userId);

    // 乐观锁更新（改 pending 任务）：version 不匹配则 0 行（上层 409）
    @Update("UPDATE tb_user_task SET title = #{title}, due_at = #{dueAt}, timezone = #{timezone}, " +
            "recurrence = #{recurrence}, recurrence_rule = #{recurrenceRule}, status = #{status}, " +
            "version = version + 1, update_time = NOW() " +
            "WHERE id = #{id} AND user_id = #{userId} AND status = 'pending' AND version = #{version}")
    int updateByIdAndVersion(UserTask task);

    // 完成：once → done；recurring → 保持 pending + due_at 滚动到下一次（由 Service 计算好再传入）
    @Update("UPDATE tb_user_task SET status = #{status}, completed_at = #{completedAt}, " +
            "due_at = #{dueAt}, last_notified_at = NULL, version = version + 1, update_time = NOW() " +
            "WHERE id = #{id} AND user_id = #{userId} AND status = 'pending' AND version = #{version}")
    int complete(@Param("id") long id, @Param("userId") String userId,
            @Param("status") String status,
            @Param("completedAt") java.time.LocalDateTime completedAt,
            @Param("dueAt") java.time.LocalDateTime dueAt,
            @Param("version") int version);

    // 软删除：置 deleted_at；幂等
    @Update("UPDATE tb_user_task SET deleted_at = NOW(), version = version + 1, update_time = NOW() " +
            "WHERE id = #{id} AND user_id = #{userId} AND deleted_at IS NULL")
    int softDelete(@Param("id") long id, @Param("userId") String userId);

    @org.apache.ibatis.annotations.Delete("DELETE FROM tb_user_task WHERE user_id = #{userId}")
    int deleteByUser(@Param("userId") String userId);
}
