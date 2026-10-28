package com.aichat.Mapper;

import java.util.List;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.aichat.Entity.ChatSession;

@Mapper
public interface ChatSessionMapper {

    // 会话首次写入时插入；已存在时只更新标题和更新时间，避免覆盖创建时间。
    // model 用 COALESCE 锁定：只在首条消息（model 非空且原值为空）时写入，后续不覆盖，实现"会话锁模型"
    @Insert("INSERT INTO tb_chat_session(user_id, session_id, title, model, create_time, update_time) " +
            "VALUES(#{userId}, #{sessionId}, #{title,jdbcType=VARCHAR}, #{model,jdbcType=VARCHAR}, #{createTime}, #{updateTime}) " +
            "ON DUPLICATE KEY UPDATE " +
            "title = COALESCE(#{title,jdbcType=VARCHAR}, title), " +
            "model = COALESCE(model, #{model,jdbcType=VARCHAR}), " +
            "update_time = #{updateTime}")
    int upsert(ChatSession session);

    // 会话列表按最近更新时间倒序，用于 Redis ZSet 过期后的恢复
    @Select("SELECT user_id, session_id, title, model, create_time, update_time " +
            "FROM tb_chat_session " +
            "WHERE user_id = #{userId} " +
            "ORDER BY update_time DESC")
    List<ChatSession> selectByUser(@Param("userId") String userId);

    @Select("SELECT title FROM tb_chat_session " +
            "WHERE user_id = #{userId} AND session_id = #{sessionId}")
    String selectTitle(@Param("userId") String userId,
            @Param("sessionId") String sessionId);

    @Select("SELECT model FROM tb_chat_session " +
            "WHERE user_id = #{userId} AND session_id = #{sessionId}")
    String selectModel(@Param("userId") String userId,
            @Param("sessionId") String sessionId);

    @Delete("DELETE FROM tb_chat_session WHERE user_id = #{userId} AND session_id = #{sessionId}")
    int deleteBySession(@Param("userId") String userId,
            @Param("sessionId") String sessionId);
}
