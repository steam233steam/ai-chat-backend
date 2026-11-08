package com.aichat.Controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/user")
@Tag(name = "用户管理", description = "验证码登录、密码登录、用户资料与登出")
public class UserController {
    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @Operation(summary = "发送短信验证码", description = "向指定手机号下发登录/注册验证码（开发模式默认输出到日志）")
    @PostMapping("/code")
    public Result<String> sendCode(@RequestParam("phone") String phone) {
        return userService.sendCode(phone);
    }

    @Operation(summary = "验证码登录", description = "手机号 + 验证码登录；新用户自动创建并标记 isNewUser")
    @PostMapping("/login")
    public Result<String> login(@Valid @RequestBody LoginFormDTO loginForm) {
        return userService.login(loginForm);
    }

    @Operation(summary = "密码登录", description = "手机号 + 密码登录，密码需 BCrypt 校验通过")
    @PostMapping("/login/password")
    public Result<String> loginByPassword(@Valid @RequestBody LoginFormDTO loginForm) {
        return userService.loginByPassword(loginForm);
    }

    @Operation(summary = "手机号 + 密码注册", description = "不需要短信验证码；成功后直接返回 token。按 IP 每日限流、同号 60 秒冷却")
    @PostMapping("/register")
    public Result<String> register(@Valid @RequestBody LoginFormDTO loginForm, HttpServletRequest request) {
        return userService.register(loginForm, clientIp(request));
    }

    /**
     * 取调用方 IP。nginx 在本机反代，取 X-Forwarded-For 第一段（真实客户端），
     * 没有就走 remoteAddr，避免把所有注册都算到 127.0.0.1 头上。
     */
    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        return request.getRemoteAddr();
    }

    @Operation(summary = "设置 / 重置密码", description = "新用户首次设置密码或已注册用户重置密码")
    @PutMapping("/set-password")
    public Result<String> setPassword(@Valid @RequestBody LoginFormDTO loginForm) {
        return userService.setPassword(loginForm);
    }

    // 修改资料
    @Operation(summary = "修改用户资料", description = "更新昵称等字段，同步刷新 Token 对应的 Redis Hash")
    @PutMapping("/profile")
    public Result<String> updateProfile(@RequestBody UserDto userDto,
            @RequestHeader("Authorization") String token) {
        return userService.updateProfile(userDto.getName(), token);
    }

    // 获取当前用户信息
    @Operation(summary = "获取当前用户信息", description = "返回当前 Token 对应的用户基础信息，含是否新用户标记")
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
    @Operation(summary = "检查是否新用户", description = "前端据此决定引导设置密码/昵称流程")
    @GetMapping("/is-new-user")
    public Result<Boolean> isNewUser() {
        UserDto user = UserHolder.getUser();
        if (user == null) {
            throw new BusinessException(401, "请先登录");
        }
        return Result.success(userService.isNewUser(user.getId()));
    }

    // 登出
    @Operation(summary = "登出", description = "使当前 Token 失效")
    @PostMapping("/logout")
    public Result<String> logout(@RequestHeader(value = "Authorization", required = false) String token) {
        if (token == null || token.isBlank()) {
            throw new BusinessException(401, "请先登录");
        }
        userService.logout(token);
        return Result.success("登出成功");
    }
}
