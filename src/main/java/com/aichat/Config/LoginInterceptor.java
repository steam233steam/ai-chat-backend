package com.aichat.Config;

import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import com.aichat.Dto.UserDto;
import com.aichat.utils.UserHolder;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 登录校验拦截器
 * 功能：检查 UserHolder 是否有用户信息，没有则返回 401
 */
@Component
public class LoginInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // CORS 预检请求不带 Token，直接放行
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }

        // 1. 从 UserHolder 获取用户
        UserDto user = UserHolder.getUser();

        // 2. 用户不存在，返回 401
        if (user == null) {
            response.setStatus(401);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":401,\"message\":\"请先登录\",\"data\":null}");
            return false;
        }

        // 3. 用户存在，放行
        return true;
    }
}
