package com.aichat.Config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.aichat.Dto.UserDto;
import com.aichat.utils.UserHolder;

class RefreshTokenInterceptorTest {

    private final RefreshTokenInterceptor interceptor = new RefreshTokenInterceptor(null);

    @AfterEach
    void cleanup() {
        UserHolder.removeUser();
    }

    @Test
    void preHandleClearsStaleUserWhenNoToken() throws Exception {
        UserDto stale = new UserDto();
        stale.setId("stale-user");
        UserHolder.saveUser(stale);

        boolean allowed = interceptor.preHandle(
                new MockHttpServletRequest(),
                new MockHttpServletResponse(),
                new Object());

        assertThat(allowed).isTrue();
        assertThat(UserHolder.getUser()).isNull();
    }

    @Test
    void afterCompletionClearsUser() throws Exception {
        UserHolder.saveUser(new UserDto());

        interceptor.afterCompletion(
                new MockHttpServletRequest(),
                new MockHttpServletResponse(),
                new Object(),
                null);

        assertThat(UserHolder.getUser()).isNull();
    }
}
