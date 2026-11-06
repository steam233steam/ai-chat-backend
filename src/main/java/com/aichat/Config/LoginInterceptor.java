package com.aichat.Config;

import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import com.aichat.Dto.UserDto;
import com.aichat.Exception.BusinessException;
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

        if (UserHolder.getUser() == null) {
            // 抛给全局异常处理器，前端拿到的仍是统一 JSON 结构
            throw new BusinessException(401, "请先登录");
        }
        return true;
    }
}
