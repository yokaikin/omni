package com.omni.ticket.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 管理端拦截器注册：/api/ticket/admin/** 统一鉴权。
 */
@Configuration
public class AdminAuthWebMvcConfig implements WebMvcConfigurer {

    private final AdminApiAuthInterceptor adminApiAuthInterceptor;

    public AdminAuthWebMvcConfig(AdminApiAuthInterceptor adminApiAuthInterceptor) {
        this.adminApiAuthInterceptor = adminApiAuthInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(adminApiAuthInterceptor)
                .addPathPatterns("/api/ticket/admin/**")
                .order(0);
    }
}
