package com.aichat.Mapper;

import java.util.List;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.aichat.Entity.ChatMessage;

@Mapper
public interface ChatMessageMapper {

    // 插入一条消息，自增 id 作为同一毫秒内消息的稳定排序依据
    @Insert("INSERT INTO tb_chat_message(user_id, session_id, role, content, create_time) " +
            "VALUES(#{userId}, #{sessionId}, #{role}, #{content}, #{createTime})")
    int insert(ChatMessage message);

    // 按写入顺序查询会话消息，用于 Redis 缓存未命中时回源
    @Select("SELECT id, user_id, session_id, role, content, create_time " +
            "FROM tb_chat_message " +
            "WHERE user_id = #{userId} AND session_id = #{sessionId} " +
            "ORDER BY id ASC")
    List<ChatMessage> selectBySession(@Param("userId") String userId,
            @Param("sessionId") String sessionId);

    @Delete("DELETE FROM tb_chat_message WHERE user_id = #{userId} AND session_id = #{sessionId}")
    int deleteBySession(@Param("userId") String userId,
            @Param("sessionId") String sessionId);
}
