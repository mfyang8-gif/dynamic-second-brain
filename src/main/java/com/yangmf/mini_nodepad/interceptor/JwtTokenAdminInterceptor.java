package com.yangmf.mini_nodepad.interceptor;


import com.fasterxml.jackson.databind.ObjectMapper;

import com.yangmf.mini_nodepad.context.BaseContext;
import com.yangmf.mini_nodepad.properties.JwtProperties;
import com.yangmf.mini_nodepad.result.Result;
import com.yangmf.mini_nodepad.service.TokenService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
@Slf4j
public class JwtTokenAdminInterceptor implements HandlerInterceptor {


    private final JwtProperties jwtProperties;


    private final TokenService tokenService;

    public JwtTokenAdminInterceptor(JwtProperties jwtProperties, TokenService tokenService) {
        this.jwtProperties = jwtProperties;
        this.tokenService = tokenService;
    }

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {

        if (!(handler instanceof HandlerMethod)) {
            return true;
        }

        String token = request.getHeader(jwtProperties.getUserTokenName());

        if (token == null || token.isEmpty()) {
            log.warn("用户请求缺少 Token: URI={}", request.getRequestURI());
            writeErrorResponse(response, 401, "缺少认证令牌，请先登录", request);
            return false;
        }

        try {
            Long userId = tokenService.validateUserToken(token);

            if (userId == null) {
                log.warn("用户 Token 无效或已过期: URI={}", request.getRequestURI());
                writeErrorResponse(response, 401, "认证令牌无效或已过期，请重新登录", request);
                return false;
            }

            tokenService.refreshToken(token);

            BaseContext.setCurrentId(userId);
            log.debug("用户认证成功: userId={}, URI={}", userId, request.getRequestURI());
            return true;
        } catch (Exception ex) {
            log.error("用户 Token 校验异常: {}", ex.getMessage());
            writeErrorResponse(response, 401, "认证失败，请重新登录", request);
            return false;
        }
    }


    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        BaseContext.removeCurrentId();  //  没有这行，线程池复用时会导致 userId 串号
    }

    private void writeErrorResponse(HttpServletResponse response, int statusCode, String message,
                                    HttpServletRequest request) throws IOException {
        response.setStatus(statusCode);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());

        Result<Void> result = Result.error(message);
        result.setCode(statusCode);
        result.setRequestId((String) request.getAttribute("requestId"));

        String json = objectMapper.writeValueAsString(result);
        response.getWriter().write(json);
        response.getWriter().flush();
    }

}
