package com.aichat.Service;

import com.aichat.Dto.LoginFormDTO;
import com.aichat.Dto.Result;

public interface UserService {

    Result<String> sendCode(String phone);

    Result<String> login(LoginFormDTO loginForm);

    void logout(String token);

    Result<String> loginByPassword(LoginFormDTO loginForm);

    Result<String> setPassword(LoginFormDTO loginForm);

    Result<String> updateProfile(String name, String token);

    // 检查是否为新用户
    Boolean isNewUser(String uid);
}
