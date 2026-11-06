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
import com.aichat.Service.SmsSender;
import com.aichat.Service.UserService;
import com.aichat.Service.support.DisabledSmsSender;
import com.aichat.Service.support.LogSmsSender;
import com.aichat.utils.PasswordUtils;
import com.aichat.utils.RegexUtils;
import com.aichat.utils.UserHolder;

import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;

@Service
public class UserServiceImpl implements UserService {
    private static final Logger log = LoggerFactory.getLogger(UserServiceImpl.class);

    private final UserMapper userMapper;
    private final UserRepository userRepository;
    private final SmsSender smsSender;

    /** 无 SmsSender Bean 时的单测兜底：true 用日志发送器，false 用 503 发送器。 */
    @Value("${app.sms.dev-mode:false}")
    private boolean smsDevMode;

    public UserServiceImpl(UserMapper userMapper, UserRepository userRepository, SmsSender smsSender) {
        this.userMapper = userMapper;
        this.userRepository = userRepository;
        this.smsSender = smsSender;
    }

    @Override
    public Result<String> sendCode(String phone) {
        if (RegexUtils.isPhoneInvalid(phone)) {
            throw new BusinessException("手机号码格式错误");
        }
        // 先预检短信能力：未配置时在生成验证码/占用 60 秒冷却之前直接 503。
        SmsSender sender = resolveSmsSender();
        sender.ensureAvailable();

        // 同一手机号 60 秒内只能发一次，防止短信轰炸
        if (!userRepository.tryAcquireSendCodeCooldown(phone)) {
            throw new BusinessException("验证码发送过于频繁，请 60 秒后再试");
        }
        String code = RandomUtil.randomNumbers(6);
        userRepository.saveCode(phone, code);
        try {
            sender.sendVerificationCode(phone, code);
        } catch (RuntimeException e) {
            // 发送失败不能留下一个“用户不知道、但 Redis 里存在”的验证码；
            // 同时释放冷却，让用户可以马上重试。清理失败只记日志，不能盖掉原始发送错误。
            try {
                userRepository.deleteCode(phone);
            } catch (Exception cleanup) {
                log.warn("发送失败后清理验证码失败: phone={}", phone, cleanup);
            }
            try {
                userRepository.deleteSendCodeCooldown(phone);
            } catch (Exception cleanup) {
                log.warn("发送失败后释放冷却失败: phone={}", phone, cleanup);
            }
            if (e instanceof BusinessException be) {
                throw be;
            }
            log.error("验证码发送失败: phone={}", phone, e);
            throw new BusinessException(503, "验证码发送失败，请稍后重试");
        }
        log.info("验证码已发送: phone={}", phone);
        return Result.success("验证码已发送");
    }

    private SmsSender resolveSmsSender() {
        if (smsSender != null) {
            return smsSender;
        }
        // 单测直接 new UserServiceImpl() 时没有 Spring 注入，按配置兜底。
        return smsDevMode ? new LogSmsSender() : new DisabledSmsSender();
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
