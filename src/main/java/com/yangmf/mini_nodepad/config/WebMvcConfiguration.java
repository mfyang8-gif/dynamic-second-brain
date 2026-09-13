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
                .excludePathPatterns("/api/v1/admin/user/login");

        registry.addInterceptor(jwtTokenUserInterceptor)
                .addPathPatterns("/api/v1/user/**")
                .excludePathPatterns(
                        "/api/v1/user/user/login",
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
