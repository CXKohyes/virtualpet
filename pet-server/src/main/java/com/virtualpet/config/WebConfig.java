package com.virtualpet.config;

import com.virtualpet.auth.AuthInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 层配置：注册鉴权拦截器。
 *
 * <p>默认所有 {@code /api/v1/**} 都需要 Bearer 令牌，只放行两个免鉴权接口：
 * 建立会话和读取游戏配置。</p>
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    /** 不需要令牌的接口。 */
    private static final String[] PUBLIC_PATHS = {
            "/api/v1/session",
            "/api/v1/game/config",
    };

    private final AuthInterceptor authInterceptor;

    public WebConfig(AuthInterceptor authInterceptor) {
        this.authInterceptor = authInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/v1/**")
                .excludePathPatterns(PUBLIC_PATHS);
    }
}
