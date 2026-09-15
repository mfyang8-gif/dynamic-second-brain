package com.yangmf.mini_nodepad.controller;

import com.yangmf.mini_nodepad.properties.JwtProperties;
import com.yangmf.mini_nodepad.result.Result;
import com.yangmf.mini_nodepad.service.TokenService;
import com.yangmf.mini_nodepad.utils.JwtUtil;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/test")
@RequiredArgsConstructor
public class TestAuthController {

    private final JwtProperties jwtProperties;

    private final JwtUtil jwtUtil;

    private final TokenService tokenService;

    /**
     * 开发者后门：一键获取测试 Token
     */
    @GetMapping("/mock-login")
    public Result<String> mockLogin() {
        Map<String, Object> claims = new HashMap<>();
        // 强行把 userId 设为 1（作为你的超级测试账号）
        // 这里的 Key（"userId" 或 "empId"）必须和你拦截器里解析的 Key 保持一致！
        claims.put("userId", 1L);

        // 签发一个超长有效期的 Token
        String token = jwtUtil.createJWT(
                jwtProperties.getUserSecretKey(),
                jwtProperties.getUserTtl(),
                claims
        );

        // 【关键】将 token 存入 Redis，否则拦截器验证时会找不到
        tokenService.saveUserToken(token, 1L);

        return Result.success(token);
    }
}