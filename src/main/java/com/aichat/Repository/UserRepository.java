package com.aichat.Repository;

import com.aichat.Dto.User;
import com.aichat.Dto.UserDto;

public interface UserRepository {
    // 校验 redis 里的验证码
    Boolean checkCode(String phone, String code);

    // 保存验证码到 redis
    void saveCode(String phone, String code);

    // 验证码发送冷却，返回 true 表示本次可以发送
    boolean tryAcquireSendCodeCooldown(String phone);

    // 保存用户信息到 redis
    void saveUser(String token, User user);

    // 删除 Redis 中的 Token
    void deleteToken(String token);

    // 删除 redis 中的验证码
    void deleteCode(String phone);

    // 同步更新 Redis 里的 Token（让后续请求也能读到新数据）
    void updateToken(String token, String name, UserDto user);

    // 标记新用户（需要设置密码/昵称）
    void markNewUser(String uid);

    // 检查是否为新用户
    Boolean isNewUser(String uid);

    // 取消新用户标记（用户已设置密码/昵称）
    void clearNewUserMark(String uid);
}
