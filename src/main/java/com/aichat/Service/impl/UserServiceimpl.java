package com.aichat.Service.impl;

import static com.aichat.utils.RedisConstants.*;

import java.time.LocalDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.aichat.Dto.LoginFormDTO;
import com.aichat.Dto.Result;
import com.aichat.Dto.User;
import com.aichat.Dto.UserDto;
import com.aichat.Exception.BusinessException;
import com.aichat.Mapper.UserMapper;
import com.aichat.Repository.UserRepository;
import com.aichat.Service.UserService;
import com.aichat.utils.PasswordUtils;
import com.aichat.utils.RegexUtils;
import com.aichat.utils.UserHolder;

import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;

@Service
public class UserServiceimpl implements UserService {
    private static final Logger log = LoggerFactory.getLogger(UserServiceimpl.class);

    @Autowired
    private UserMapper userMapper;
    @Autowired
    private UserRepository userRepository;

    /** 本地开发时把验证码打到日志；接入短信服务后通过配置关闭 */
    @Value("${app.sms.dev-mode:true}")
    private boolean smsDevMode;

    @Override
    public Result<String> sendCode(String phone) {
        if (RegexUtils.isPhoneInvalid(phone)) {
            throw new BusinessException("手机号码格式错误");
        }
        // 同一手机号 60 秒内只能发一次，防止短信轰炸
        if (!userRepository.tryAcquireSendCodeCooldown(phone)) {
            throw new BusinessException("验证码发送过于频繁，请 60 秒后再试");
        }
        String code = RandomUtil.randomNumbers(6);
        userRepository.saveCode(phone, code);
        // TODO: 接入短信服务，当前仅存 Redis
        if (smsDevMode) {
            // 生产日志不回显完整验证码，避免手机号 + 验证码成对泄露；仅打印前 2 位脱敏
            log.warn("[DEV] 未接入短信服务，验证码已打印在日志: phone={}, code={}**",
                    phone, code.substring(0, 2));
        } else {
            log.info("验证码已发送: phone={}", phone);
        }
        return Result.success("验证码已发送");
    }

    @Override
    public Result<String> login(LoginFormDTO loginForm) {
        if (loginForm == null) {
            throw new BusinessException("请求参数不能为空");
        }
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            throw new BusinessException("手机号码格式错误");
        }
        if (RegexUtils.isCodeInvalid(loginForm.getCode())) {
            throw new BusinessException("验证码格式错误");
        }
        if (!userRepository.checkCode(phone, loginForm.getCode())) {
            throw new BusinessException("验证码错误或已过期");
        }
        // 验证码一次性使用：校验通过后立即删除，避免重放（新用户同样要删）
        userRepository.deleteCode(phone);

        User user = userMapper.getUserByPhone(phone);
        if (user == null) {
            user = createUserByPhone(phone);
            // 标记为新用户（需要设置密码/昵称）
            userRepository.markNewUser(user.getUid());
        }

        // 生成 token 并保存用户信息到 Redis
        String token = UUID.randomUUID().toString(true);
        userRepository.saveUser(token, user);
        return Result.success(token);
    }

    private User createUserByPhone(String phone) {
        LocalDateTime now = LocalDateTime.now();
        String uid = USER_ID_PREFIX + RandomUtil.randomString(10);

        User user = new User();
        user.setUid(uid);
        user.setName("用户" + phone.substring(phone.length() - 4));
        user.setPhone(phone);
        // 首次注册不设置密码，必须走验证码登录后由用户自己设置
        user.setPassword(null);
        user.setCreateTime(now);
        user.setUpdateTime(now);
        userMapper.insert(user);
        return user;
    }

    @Override
    public void logout(String token) {
        userRepository.deleteToken(token);
    }

    @Override
    public Result<String> loginByPassword(LoginFormDTO loginForm) {
        if (loginForm == null) {
            throw new BusinessException("请求参数不能为空");
        }
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            throw new BusinessException("手机号码格式错误");
        }
        String password = loginForm.getPassword();
        if (password == null || password.isBlank()) {
            throw new BusinessException("密码不能为空");
        }

        User user = userMapper.getUserByPhone(phone);
        // 统一提示，避免暴露手机号是否已注册
        if (user == null || user.getPassword() == null || user.getPassword().isBlank()) {
            throw new BusinessException("手机号或密码错误");
        }
        if (!PasswordUtils.matches(password, user.getPassword())) {
            throw new BusinessException("手机号或密码错误");
        }

        String token = UUID.randomUUID().toString(true);
        userRepository.saveUser(token, user);
        return Result.success(token);
    }

    @Override
    public Result<String> setPassword(LoginFormDTO loginForm) {
        UserDto current = UserHolder.getUser();
        if (current == null) {
            throw new BusinessException(401, "请先登录");
        }
        if (loginForm == null) {
            throw new BusinessException("请求参数不能为空");
        }

        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            throw new BusinessException("手机号码格式错误");
        }
        // 只能给当前登录账号设置密码，防止拿着别人的验证码改别人密码
        if (!phone.equals(current.getPhone())) {
            throw new BusinessException("手机号与当前登录账号不一致");
        }
        if (RegexUtils.isCodeInvalid(loginForm.getCode())) {
            throw new BusinessException("验证码格式错误");
        }
        // 先校验密码强度，避免密码不合法时白白消耗掉验证码
        if (RegexUtils.isPasswordInvalid(loginForm.getPassword())) {
            throw new BusinessException("密码需为 4-32 位字母、数字或下划线");
        }
        if (!userRepository.checkCode(phone, loginForm.getCode())) {
            throw new BusinessException("验证码错误或已过期");
        }

        userRepository.deleteCode(phone);
        String encoded = PasswordUtils.encode(loginForm.getPassword());
        userMapper.updatePassword(current.getId(), encoded, LocalDateTime.now());
        // 取消新用户标记
        userRepository.clearNewUserMark(current.getId());
        return Result.success("密码设置成功");
    }

    @Override
    public Result<String> updateProfile(String name, String token) {
        UserDto user = UserHolder.getUser();
        if (user == null) {
            throw new BusinessException(401, "请先登录");
        }
        if (name == null || name.isBlank()) {
            throw new BusinessException("昵称不能为空");
        }
        String trimmed = name.strip();
        if (trimmed.length() > 20) {
            throw new BusinessException("昵称长度不能超过 20 个字符");
        }

        userMapper.updateProfile(user.getId(), trimmed, LocalDateTime.now());
        // 同步更新 UserHolder 与 Redis 里的 Token 缓存
        user.setName(trimmed);
        userRepository.updateToken(token, trimmed, user);
        // 注意：新用户标记只在 setPassword 时清除，改昵称不清除
        return Result.success("修改成功");
    }

    @Override
    public Boolean isNewUser(String uid) {
        return userRepository.isNewUser(uid);
    }
}
