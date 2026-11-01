package com.aichat.Config;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import com.aichat.Dto.UserDto;
import com.aichat.utils.UserHolder;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import static com.aichat.utils.RedisConstants.*;

/**
 * Token刷新拦截器
 * 功能：
 * 1. 从请求头获取Token
 * 2. 从Redis读取用户信息
 * 3. 存入UserHolder
 * 4. 刷新Token过期时间
 */
@Component
public class RefreshTokenInterceptor implements HandlerInterceptor {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 先清理线程上可能残留的用户。
        // SSE/异步请求的 afterCompletion 可能不在原 Tomcat 线程执行，
        // 若不在入口处清理，后续无 Token 请求可能复用到上一个请求的用户。
        UserHolder.removeUser();

        // 1. 从请求头获取Token
        String token = request.getHeader("Authorization");
        
        // 2. Token为空，放行（让LoginInterceptor拦截）
        if (token == null || token.isEmpty()) {
            return true;
        }
        
        // 3. 从Redis获取用户信息
        String key = LOGIN_TOKEN_KEY_PREFIX + token;
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(key);
        
        // 4. 用户不存在，放行（让LoginInterceptor拦截）
        if (userMap.isEmpty()) {
            return true;
        }
        
        // 5. 构建UserDto
        UserDto userDto = new UserDto();
        userDto.setId((String) userMap.get("id"));
        userDto.setName((String) userMap.get("name"));
        userDto.setPhone((String) userMap.get("phone"));
        
        // 6. 存入UserHolder
        UserHolder.saveUser(userDto);
        
        // 7. 刷新Token过期时间（续期）
        stringRedisTemplate.expire(key, TOKEN_TTL_DAYS, TimeUnit.DAYS);
        
        // 8. 放行
        return true;
    }
    
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        // 请求结束后清除UserHolder，防止内存泄漏
        UserHolder.removeUser();
    }
}
