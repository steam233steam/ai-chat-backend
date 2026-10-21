package com.aichat.Service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.aichat.Dto.LoginFormDTO;
import com.aichat.Dto.Result;
import com.aichat.Dto.User;
import com.aichat.Exception.BusinessException;
import com.aichat.Mapper.UserMapper;
import com.aichat.Repository.UserRepository;

class UserServiceimplTest {

    private UserMapper userMapper;
    private UserRepository userRepository;
    private UserServiceimpl service;

    @BeforeEach
    void setUp() {
        userMapper = mock(UserMapper.class);
        userRepository = mock(UserRepository.class);
        service = new UserServiceimpl();
        ReflectionTestUtils.setField(service, "userMapper", userMapper);
        ReflectionTestUtils.setField(service, "userRepository", userRepository);
    }

    @Test
    void shouldConsumeCodeForBrandNewUser() {
        LoginFormDTO form = new LoginFormDTO();
        form.setPhone("13800000000");
        form.setCode("123456");
        when(userRepository.checkCode("13800000000", "123456")).thenReturn(true);
        when(userMapper.getUserByPhone("13800000000")).thenReturn(null);

        Result<String> result = service.login(form);

        assertThat(result.getCode()).isEqualTo(200);
        assertThat(result.getData()).isNotBlank();
        // 关键回归点：新用户也必须消费掉验证码，避免 5 分钟内重放
        verify(userRepository).deleteCode("13800000000");
        verify(userRepository).markNewUser(anyString());
        verify(userRepository).saveUser(anyString(), any(User.class));
    }

    @Test
    void shouldNotMarkExistingUserAsNew() {
        LoginFormDTO form = new LoginFormDTO();
        form.setPhone("13800000000");
        form.setCode("123456");
        when(userRepository.checkCode("13800000000", "123456")).thenReturn(true);
        when(userMapper.getUserByPhone("13800000000")).thenReturn(existingUser());

        service.login(form);

        verify(userRepository).deleteCode("13800000000");
        verify(userRepository, never()).markNewUser(anyString());
    }

    @Test
    void passwordLoginShouldFailWhenPasswordNotSet() {
        LoginFormDTO form = new LoginFormDTO();
        form.setPhone("13800000000");
        form.setPassword("123456");
        when(userMapper.getUserByPhone("13800000000")).thenReturn(existingUser());

        assertThatThrownBy(() -> service.loginByPassword(form))
                .isInstanceOf(BusinessException.class)
                .hasMessage("手机号或密码错误");
    }

    private User existingUser() {
        User user = new User();
        user.setUid("u1");
        user.setPhone("13800000000");
        user.setName("用户0000");
        user.setPassword(null);
        return user;
    }
}
