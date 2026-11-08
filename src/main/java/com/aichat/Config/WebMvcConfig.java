package com.aichat.Config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置：注册拦截器、配置跨域
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    /** 无需登录即可访问的接口 */
    private static final String[] PUBLIC_PATHS = {
            "/user/code",
            "/user/login",
            "/user/login/password",
            "/user/register",
            "/chat/models",
            "/error"
    };

    private final RefreshTokenInterceptor refreshTokenInterceptor;
    private final LoginInterceptor loginInterceptor;
    private final String[] allowedOrigins;

    public WebMvcConfig(RefreshTokenInterceptor refreshTokenInterceptor,
            LoginInterceptor loginInterceptor,
            @Value("${app.cors.allowed-origins:http://localhost:*,http://127.0.0.1:*}") String[] allowedOrigins) {
        this.refreshTokenInterceptor = refreshTokenInterceptor;
        this.loginInterceptor = loginInterceptor;
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 顺序即执行顺序：先把 Token 解析成 UserHolder，再判断是否登录
        registry.addInterceptor(refreshTokenInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns(PUBLIC_PATHS);

        registry.addInterceptor(loginInterceptor)
                .addPathPatterns("/chat/**", "/user/**", "/mcp/**")
                .excludePathPatterns(PUBLIC_PATHS);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // 白名单来自 app.cors.allowed-origins，默认只放本机端口；上线时改成真实域名
        registry.addMapping("/**")
                .allowedOriginPatterns(allowedOrigins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
