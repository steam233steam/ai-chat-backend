package com.aichat.utils;

import com.aichat.Dto.UserDto;

public class UserHolder {
    // 用户信息
    private static ThreadLocal<UserDto> threadLocal = new ThreadLocal<>();

    // 保存
    public static void saveUser(UserDto user) {
        threadLocal.set(user);
    }

    // 获取
    public static UserDto getUser() {
        return threadLocal.get();
    }

    // 移除
    public static void removeUser() {
        threadLocal.remove();
    }
}
