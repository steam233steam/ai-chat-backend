package com.aichat.Mapper;

import java.util.List;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import com.aichat.Entity.UserMemory;

@Mapper
public interface UserMemoryMapper {

    // 插入一条记忆，自增 id 回填；create_time/update_time 由库默认值生成
    @Insert("INSERT INTO tb_user_memory(user_id, mem_key, content, mem_type, status, confidence, " +
            "source, source_message_id, replaces_id, expires_at, version) " +
            "VALUES(#{userId}, #{memKey}, #{content}, #{memType}, #{status}, #{confidence}, " +
            "#{source}, #{sourceMessageId}, #{replacesId}, #{expiresAt}, 0)")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(UserMemory memory);

    // 查当前用户某条记忆（带归属校验，跨用户查不到 → 上层返回 404）
    @Select("SELECT id, user_id, mem_key, content, mem_type, status, confidence, source, " +
            "source_message_id, replaces_id, expires_at, version, create_time, update_time " +
            "FROM tb_user_memory WHERE id = #{id} AND user_id = #{userId}")
    UserMemory selectById(@Param("id") long id, @Param("userId") String userId);

    // 列表：默认只返回 active 且未过期；stable/dynamic 分开按 confidence+update_time 排序取配额
    @Select("SELECT id, user_id, mem_key, content, mem_type, status, confidence, source, " +
            "source_message_id, replaces_id, expires_at, version, create_time, update_time " +
            "FROM tb_user_memory " +
            "WHERE user_id = #{userId} AND status = 'active' " +
            "AND (mem_type = #{memType} OR mem_type IS NULL) " +
            "AND (expires_at IS NULL OR expires_at > #{now}) " +
            "ORDER BY confidence DESC, update_time DESC LIMIT #{limit}")
    List<UserMemory> selectActiveByUserAndType(@Param("userId") String userId,
            @Param("memType") String memType,
            @Param("now") java.time.LocalDateTime now,
            @Param("limit") int limit);

    // 生效列表：active 且未过期，按类型/更新时间排序；limit 由上层 1-100 约束
    @Select("SELECT id, user_id, mem_key, content, mem_type, status, confidence, source, " +
            "source_message_id, replaces_id, expires_at, version, create_time, update_time " +
            "FROM tb_user_memory " +
            "WHERE user_id = #{userId} AND status = 'active' " +
            "AND (expires_at IS NULL OR expires_at > #{now}) " +
            "ORDER BY mem_type, update_time DESC LIMIT #{limit}")
    List<UserMemory> selectActiveByUser(@Param("userId") String userId,
            @Param("now") java.time.LocalDateTime now,
            @Param("limit") int limit);

    // 全量导出（含 superseded，排除 deleted），供用户查看/迁移
    @Select("SELECT id, user_id, mem_key, content, mem_type, status, confidence, source, " +
            "source_message_id, replaces_id, expires_at, version, create_time, update_time " +
            "FROM tb_user_memory " +
            "WHERE user_id = #{userId} AND status != 'deleted' " +
            "ORDER BY mem_type, mem_key, update_time DESC")
    List<UserMemory> selectExportByUser(@Param("userId") String userId);

    // 当前 active 且未过期的记忆条数（每用户上限兜底）
    @Select("SELECT COUNT(*) FROM tb_user_memory " +
            "WHERE user_id = #{userId} AND status = 'active' " +
            "AND (expires_at IS NULL OR expires_at > #{now})")
    int countActive(@Param("userId") String userId,
            @Param("now") java.time.LocalDateTime now);

    // 相同 active 内容去重（手动创建 409 / AI 提取不重复插入），取最近一条
    @Select("SELECT id, user_id, mem_key, content, mem_type, status, confidence, source, " +
            "source_message_id, replaces_id, expires_at, version, create_time, update_time " +
            "FROM tb_user_memory " +
            "WHERE user_id = #{userId} AND status = 'active' AND content = #{content} " +
            "AND (expires_at IS NULL OR expires_at > #{now}) " +
            "ORDER BY update_time DESC LIMIT 1")
    UserMemory selectActiveByContent(@Param("userId") String userId,
            @Param("content") String content,
            @Param("now") java.time.LocalDateTime now);

    // 乐观锁更新：只改 active 且未过期；version 不匹配则 0 行（上层 409）
    @Update("UPDATE tb_user_memory SET content = #{m.content}, mem_type = #{m.memType}, " +
            "expires_at = #{m.expiresAt}, version = version + 1, update_time = #{now} " +
            "WHERE id = #{m.id} AND user_id = #{m.userId} AND status = 'active' " +
            "AND (expires_at IS NULL OR expires_at > #{now}) AND version = #{m.version}")
    int updateByIdAndVersion(@Param("m") UserMemory memory,
            @Param("now") java.time.LocalDateTime now);

    // 软删除：置 deleted；幂等（重复删不会报错）
    @Update("UPDATE tb_user_memory SET status = 'deleted', version = version + 1, update_time = NOW() " +
            "WHERE id = #{id} AND user_id = #{userId} AND status != 'deleted'")
    int softDelete(@Param("id") long id, @Param("userId") String userId);

    // 把旧记忆置 superseded（被新记忆替换）；必须属于当前用户且 active
    @Update("UPDATE tb_user_memory SET status = 'superseded', version = version + 1, update_time = NOW() " +
            "WHERE id = #{id} AND user_id = #{userId} AND status = 'active'")
    int markSuperseded(@Param("id") long id, @Param("userId") String userId);

    @Delete("DELETE FROM tb_user_memory WHERE user_id = #{userId}")
    int deleteByUser(@Param("userId") String userId);
}
