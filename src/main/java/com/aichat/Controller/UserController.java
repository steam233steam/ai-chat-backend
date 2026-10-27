package com.aichat.Controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.aichat.Dto.LoginFormDTO;
import com.aichat.Dto.Result;
import com.aichat.Dto.UserDto;
import com.aichat.Exception.BusinessException;
import com.aichat.Service.UserService;
import com.aichat.utils.UserHolder;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/user")
public class UserController {
    @Autowired
    private UserService userService;

    @PostMapping("/code")
    public Result<String> sendCode(@RequestParam("phone") String phone) {
        return userService.sendCode(phone);
    }

    @PostMapping("/login")
    public Result<String> login(@Valid @RequestBody LoginFormDTO loginForm) {
        return userService.login(loginForm);
    }

    @PostMapping("/login/password")
    public Result<String> loginByPassword(@Valid @RequestBody LoginFormDTO loginForm) {
        return userService.loginByPassword(loginForm);
    }

    // 设置密码（新用户首次设置，或登录后重置）
    @PutMapping("/set-password")
    public Result<String> setPassword(@Valid @RequestBody LoginFormDTO loginForm) {
        return userService.setPassword(loginForm);
    }

    // 修改资料
    @PutMapping("/profile")
    public Result<String> updateProfile(@RequestBody UserDto userDto,
            @RequestHeader("Authorization") String token) {
        return userService.updateProfile(userDto.getName(), token);
    }

    // 获取当前用户信息
    @GetMapping("/me")
    public Result<UserDto> me() {
        UserDto user = UserHolder.getUser();
        if (user == null) {
            throw new BusinessException(401, "请先登录");
        }
        UserDto result = new UserDto();
        result.setId(user.getId());
        result.setName(user.getName());
        result.setPhone(user.getPhone());
        result.setIsNewUser(userService.isNewUser(user.getId()));
        return Result.success(result);
    }

    // 检查是否为新用户（独立接口，方便前端调用）
    @GetMapping("/is-new-user")
    public Result<Boolean> isNewUser() {
        UserDto user = UserHolder.getUser();
        if (user == null) {
            throw new BusinessException(401, "请先登录");
        }
        return Result.success(userService.isNewUser(user.getId()));
    }

    // 登出
    @PostMapping("/logout")
    public Result<String> logout(@RequestHeader(value = "Authorization", required = false) String token) {
        if (token == null || token.isBlank()) {
            throw new BusinessException(401, "请先登录");
        }
        userService.logout(token);
        return Result.success("登出成功");
    }
}
