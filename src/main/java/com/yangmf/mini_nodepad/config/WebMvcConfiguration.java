package com.yangmf.mini_nodepad.config;

import com.github.pagehelper.PageInterceptor;
import com.yangmf.mini_nodepad.interceptor.JwtTokenAdminInterceptor;
import com.yangmf.mini_nodepad.interceptor.JwtTokenUserInterceptor;
import com.yangmf.mini_nodepad.json.JacksonObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.plugin.Interceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

@Slf4j
@Configuration
public class WebMvcConfiguration implements WebMvcConfigurer {


    private final JwtTokenUserInterceptor jwtTokenUserInterceptor;


    private final JwtTokenAdminInterceptor jwtTokenAdminInterceptor;

    public WebMvcConfiguration(JwtTokenUserInterceptor jwtTokenUserInterceptor, JwtTokenAdminInterceptor jwtTokenAdminInterceptor) {
        this.jwtTokenUserInterceptor = jwtTokenUserInterceptor;
        this.jwtTokenAdminInterceptor = jwtTokenAdminInterceptor;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/webjars/**")
                .addResourceLocations("classpath:/META-INF/resources/webjars/");

        registry.addResourceHandler("/doc.html")
                .addResourceLocations("classpath:/META-INF/resources/");

        registry.addResourceHandler("/swagger-ui/**")
                .addResourceLocations("classpath:/META-INF/resources/swagger-ui/");

        registry.addResourceHandler("/v3/api-docs/**")
                .addResourceLocations("classpath:/META-INF/resources/");

        registry.addResourceHandler("/swagger-resources/**")
                .addResourceLocations("classpath:/META-INF/resources/");

        registry.addResourceHandler("/static/**")
                .addResourceLocations("classpath:/static/");
    }

    @Bean
    public Interceptor[] plugins() {
        return new Interceptor[]{new PageInterceptor()};
    }

    @Bean
    @Primary // 加上 @Primary，告诉 Spring 优先使用我们自定义的这个实例
    public ObjectMapper objectMapper() {
        return new JacksonObjectMapper();
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        log.info("开始注册自定义拦截器...");

        registry.addInterceptor(jwtTokenAdminInterceptor)
                .addPathPatterns("/api/v1/admin/**")
                .excludePathPatterns("/api/v1/admin/user/login",
                        "/actuator/**");


        registry.addInterceptor(jwtTokenUserInterceptor)
                .addPathPatterns("/api/v1/user/**", "/api/v1/books/**", "/api/v1/pages/**",
                        "/api/v1/sessions/**", "/api/v1/chat/**", "/api/v1/workspace/**",
                        "/api/v1/studio/**", "/api/v1/files/**", "/api/v1/search/**",
                        // 2026-10-11 补：SemanticMemoryController 的 /api/v1/memory/**
                        // 依赖 BaseContext.getCurrentId() 拿 userId，本处漏配会让它
                        // 每次请求都抛 UnauthorizedException("未登录或登录已过期")，
                        // 即"带着有效 token 也 100% 401"。凡新开 @RestController
                        // 且从 BaseContext 取身份的，必须同步登记到本白名单。
                        "/api/v1/memory/**",
                        // 2026-10-12 补：ChunkReferenceController 的 /api/v1/knowledge/**
                        //（引用卡片展开拉分块原文）漏配会导致点击引用 chip 即 401 被踢下线
                        "/api/v1/knowledge/**")
                .excludePathPatterns(
                        "/api/v1/user/user/login",
                        // 2026-10-12 补：Actuator 端点（用户拍板只暴露 metrics,health）。
                        // 当前 addPathPatterns 只覆盖 /api/v1/**，actuator 本就拦不到；
                        // 这里显式登记是防御性的 —— 一旦今后有人扩大匹配前缀，
                        // 监控端点不会突然变成"要登录才能看"，也不会被 JWT 逻辑连累。
                        "/actuator/**",
                        "/doc.html",
                        "/doc.html/**",
                        "/swagger-ui/**",
                        "/swagger-ui.html",
                        "/v3/api-docs/**",
                        "/swagger-resources/**",
                        "/webjars/**",
                        "/static/**",
                        "/favicon.ico",
                        "/error"
                );
    }
}
