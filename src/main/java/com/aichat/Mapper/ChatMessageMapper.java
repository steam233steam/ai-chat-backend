package com.aichat.Mapper;

import java.util.List;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.aichat.Entity.ChatMessage;

@Mapper
public interface ChatMessageMapper {

    // 插入一条消息，自增 id 作为同一毫秒内消息的稳定排序依据；model 记录本轮实际使用的模型
    @Insert("INSERT INTO tb_chat_message(user_id, session_id, role, content, model, create_time) " +
            "VALUES(#{userId}, #{sessionId}, #{role}, #{content}, #{model}, #{createTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(ChatMessage message);

    // 按写入顺序查询会话消息，用于 Redis 缓存未命中时回源
    @Select("SELECT id, user_id, session_id, role, content, model, create_time " +
            "FROM tb_chat_message " +
            "WHERE user_id = #{userId} AND session_id = #{sessionId} " +
            "ORDER BY id ASC")
    List<ChatMessage> selectBySession(@Param("userId") String userId,
            @Param("sessionId") String sessionId);

    // 最新一页：先倒序取 limit 条，调用方再反转成升序
    @Select("SELECT id, user_id, session_id, role, content, model, create_time " +
            "FROM tb_chat_message " +
            "WHERE user_id = #{userId} AND session_id = #{sessionId} " +
            "ORDER BY id DESC LIMIT #{limit}")
    List<ChatMessage> selectLatestBySession(@Param("userId") String userId,
            @Param("sessionId") String sessionId,
            @Param("limit") int limit);

    // 游标分页：id < beforeId，倒序取 limit 条，调用方再反转成升序
    @Select("SELECT id, user_id, session_id, role, content, model, create_time " +
            "FROM tb_chat_message " +
            "WHERE user_id = #{userId} AND session_id = #{sessionId} AND id < #{beforeId} " +
            "ORDER BY id DESC LIMIT #{limit}")
    List<ChatMessage> selectBySessionBeforeId(@Param("userId") String userId,
            @Param("sessionId") String sessionId,
            @Param("beforeId") long beforeId,
            @Param("limit") int limit);

    @Delete("DELETE FROM tb_chat_message WHERE user_id = #{userId} AND session_id = #{sessionId}")
    int deleteBySession(@Param("userId") String userId,
            @Param("sessionId") String sessionId);
}
