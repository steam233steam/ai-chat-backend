package com.aichat.Mapper;

import java.time.LocalDateTime;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import com.aichat.Dto.User;

@Mapper
public interface UserMapper {
    // 插入用户
    @Insert("INSERT INTO tb_user(uid, name, phone, password, create_time, update_time) " +
            "VALUES(#{uid}, #{name}, #{phone}, #{password}, #{createTime}, #{updateTime})")
    void insert(User user);

    // 根据手机号查询用户
    @Select("SELECT * FROM tb_user WHERE phone = #{phone}")
    User getUserByPhone(@Param("phone") String phone);

    // 更新密码
    @Update("UPDATE tb_user SET password = #{password}, update_time = #{updateTime} WHERE uid = #{uid}")
    void updatePassword(@Param("uid") String uid, @Param("password") String password,
            @Param("updateTime") LocalDateTime updateTime);

    // 更新用户资料（昵称等）
    @Update("UPDATE tb_user SET name = #{name}, update_time = #{updateTime} WHERE uid = #{uid}")
    void updateProfile(@Param("uid") String uid, @Param("name") String name,
            @Param("updateTime") LocalDateTime updateTime);
}
