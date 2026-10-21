package com.aichat.Config;

import org.springframework.beans.factory.annotation.Autowired;
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
            "/error"
    };

    @Autowired
    private RefreshTokenInterceptor refreshTokenInterceptor;

    @Autowired
    private LoginInterceptor loginInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 1. 先解析 Token，把用户放进 UserHolder，并续期
        registry.addInterceptor(refreshTokenInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns(PUBLIC_PATHS);

        // 2. 再校验登录态（预检请求、公开接口不校验）
        registry.addInterceptor(loginInterceptor)
                .addPathPatterns("/chat/**", "/user/**")
                .excludePathPatterns(PUBLIC_PATHS);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // 本地开发时允许常见前端端口跨域；生产环境请改成具体域名
        registry.addMapping("/**")
                .allowedOriginPatterns("http://localhost:*", "http://127.0.0.1:*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
