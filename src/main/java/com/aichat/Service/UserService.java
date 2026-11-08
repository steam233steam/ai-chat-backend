package com.aichat.Service;

import com.aichat.Dto.LoginFormDTO;
import com.aichat.Dto.Result;

public interface UserService {

    Result<String> sendCode(String phone);

    Result<String> login(LoginFormDTO loginForm);

    void logout(String token);

    Result<String> loginByPassword(LoginFormDTO loginForm);

    /**
     * 手机号 + 密码注册（不需要短信验证码），成功后直接返回登录 token。
     *
     * @param clientIp 调用方 IP，用于每日注册次数限流
     */
    Result<String> register(LoginFormDTO loginForm, String clientIp);

    Result<String> setPassword(LoginFormDTO loginForm);

    Result<String> updateProfile(String name, String token);

    // 检查是否为新用户
    Boolean isNewUser(String uid);
}
