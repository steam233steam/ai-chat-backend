package com.aichat.Mapper;

import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import com.aichat.Entity.PendingAction;

@Mapper
public interface PendingActionMapper {

    // 插入一条待确认动作（自增 id 回填）；pending_slot 生成列 + 唯一键保证同一 user+session 只有一个 pending
    @Insert("INSERT INTO tb_pending_action(user_id, session_id, action_type, payload, status, expires_at, " +
            "source_message_id) " +
            "VALUES(#{userId}, #{sessionId}, #{actionType}, #{payload}, 'pending', #{expiresAt}, #{sourceMessageId})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(PendingAction action);

    // 查当前 session 的 pending（带归属校验）；过期时由 Service 判 expires_at 并置 expired
    @Select("SELECT id, user_id, session_id, action_type, payload, status, expires_at, source_message_id, " +
            "create_time, update_time " +
            "FROM tb_pending_action WHERE id = #{id} AND user_id = #{userId}")
    PendingAction selectById(@Param("id") long id, @Param("userId") String userId);

    // 查当前 session 的 pending（按 user+session 定位）；同一 session 只保留一个 pending
    @Select("SELECT id, user_id, session_id, action_type, payload, status, expires_at, source_message_id, " +
            "create_time, update_time " +
            "FROM tb_pending_action " +
            "WHERE user_id = #{userId} AND session_id = #{sessionId} AND status = 'pending'")
    PendingAction selectPendingBySession(@Param("userId") String userId,
            @Param("sessionId") String sessionId);

    // 原子确认：仅 pending 可确认（幂等关键）；更新失败说明已被确认/拒绝/过期
    @Update("UPDATE tb_pending_action SET status = 'confirmed', update_time = NOW() " +
            "WHERE id = #{id} AND user_id = #{userId} AND status = 'pending'")
    int markConfirmed(@Param("id") long id, @Param("userId") String userId);

    // 原子拒绝：仅 pending 可拒绝
    @Update("UPDATE tb_pending_action SET status = 'rejected', update_time = NOW() " +
            "WHERE id = #{id} AND user_id = #{userId} AND status = 'pending'")
    int markRejected(@Param("id") long id, @Param("userId") String userId);

    // 把同一 user+session 的旧 pending 置 expired（新提案前调用，避免"对"指错对象）
    @Update("UPDATE tb_pending_action SET status = 'expired', update_time = NOW() " +
            "WHERE user_id = #{userId} AND session_id = #{sessionId} AND status = 'pending'")
    int expireSession(@Param("userId") String userId, @Param("sessionId") String sessionId);

    // 懒过期：到点未确认的 pending 统一置 expired（可选定时清理 + 查询时兜底）
    @Update("UPDATE tb_pending_action SET status = 'expired', update_time = NOW() " +
            "WHERE user_id = #{userId} AND status = 'pending' AND expires_at <= NOW()")
    int expireDue(@Param("userId") String userId);

    @org.apache.ibatis.annotations.Delete("DELETE FROM tb_pending_action WHERE user_id = #{userId}")
    int deleteByUser(@Param("userId") String userId);

    /** P1.3 级联清理：删某会话的全部 pending 记录（会话删除时调用）。 */
    @org.apache.ibatis.annotations.Delete("DELETE FROM tb_pending_action WHERE user_id = #{userId} AND session_id = #{sessionId}")
    int deleteBySession(@Param("userId") String userId, @Param("sessionId") String sessionId);

    @org.apache.ibatis.annotations.Delete("DELETE FROM tb_pending_action")
    int deleteAll();

    @Select("SELECT COUNT(*) FROM tb_pending_action")
    long count();
}
