package com.aichat.Repository.impl;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import com.aichat.Dto.User;
import com.aichat.Dto.UserDto;
import com.aichat.Repository.UserRepository;
import com.aichat.utils.UserHolder;

import static com.aichat.utils.RedisConstants.*;

@Repository
public class UserRepositoryimpl implements UserRepository {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Boolean checkCode(String phone, String code) {
        String cachecode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY_PREFIX + phone);
        if (cachecode == null || code == null) {
            return false;
        }
        return cachecode.equals(code);
    }

    @Override
    public void saveCode(String phone, String code) {
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY_PREFIX + phone, code, CODE_TTL_MINUTES, TimeUnit.MINUTES);
    }

    @Override
    public boolean tryAcquireSendCodeCooldown(String phone) {
        String key = LOGIN_CODE_COOLDOWN_KEY_PREFIX + phone;
        return Boolean.TRUE.equals(stringRedisTemplate.opsForValue()
                .setIfAbsent(key, "1", CODE_SEND_COOLDOWN_SECONDS, TimeUnit.SECONDS));
    }

    @Override
    public void saveUser(String token, User user) {
        // 将 User 对象转为 HashMap 存储（只存必要字段）
        Map<String, Object> userMap = new HashMap<>();
        userMap.put("id", user.getUid());
        userMap.put("name", user.getName());
        userMap.put("phone", user.getPhone());
        // 保存用户信息到 redis
        stringRedisTemplate.opsForHash().putAll(LOGIN_TOKEN_KEY_PREFIX + token, userMap);
        // 设置 token 有效期
        stringRedisTemplate.expire(LOGIN_TOKEN_KEY_PREFIX + token, TOKEN_TTL_DAYS, TimeUnit.DAYS);
    }

    @Override
    public void deleteToken(String token) {
        stringRedisTemplate.delete(LOGIN_TOKEN_KEY_PREFIX + token);
        // 清除 UserHolder
        UserHolder.removeUser();
    }

    @Override
    public void deleteCode(String phone) {
        stringRedisTemplate.delete(LOGIN_CODE_KEY_PREFIX + phone);
    }

    @Override
    public void updateToken(String token, String name, UserDto user) {
        if (token != null && !token.isEmpty()) {
            Map<String, Object> userMap = new HashMap<>();
            userMap.put("id", user.getId());
            userMap.put("name", name);
            userMap.put("phone", user.getPhone());
            stringRedisTemplate.opsForHash().putAll(LOGIN_TOKEN_KEY_PREFIX + token, userMap);
            stringRedisTemplate.expire(LOGIN_TOKEN_KEY_PREFIX + token, TOKEN_TTL_DAYS, TimeUnit.DAYS);
        }
    }

    @Override
    public void markNewUser(String uid) {
        stringRedisTemplate.opsForValue().set(NEW_USER_MARK_KEY_PREFIX + uid, "1",
                NEW_USER_MARK_TTL_DAYS, TimeUnit.DAYS);
    }

    @Override
    public Boolean isNewUser(String uid) {
        return Boolean.TRUE.equals(stringRedisTemplate.hasKey(NEW_USER_MARK_KEY_PREFIX + uid));
    }

    @Override
    public void clearNewUserMark(String uid) {
        stringRedisTemplate.delete(NEW_USER_MARK_KEY_PREFIX + uid);
    }
}
