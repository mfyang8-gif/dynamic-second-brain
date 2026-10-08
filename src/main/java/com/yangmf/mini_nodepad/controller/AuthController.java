package com.yangmf.mini_nodepad.controller;

import com.yangmf.mini_nodepad.pojo.dto.auth.AuthResponse;
import com.yangmf.mini_nodepad.pojo.dto.auth.LoginRequest;
import com.yangmf.mini_nodepad.pojo.dto.auth.RegisterRequest;
import com.yangmf.mini_nodepad.result.Result;
import com.yangmf.mini_nodepad.service.AuthService;
import com.yangmf.mini_nodepad.utils.IpUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Tag(name = "认证模块", description = "用户注册与登录（公开端点，无需 Token）")
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    @Operation(summary = "用户注册", description = "创建新账号，注册成功后自动登录并返回 Token")
    public Result<AuthResponse> register(@Valid @RequestBody RegisterRequest request,
                                          HttpServletRequest httpRequest) {
        return Result.success(authService.register(request, IpUtils.getIp(httpRequest)));
    }

    @PostMapping("/login")
    @Operation(summary = "用户登录", description = "使用用户名或邮箱 + 密码登录，返回 JWT Token")
    public Result<AuthResponse> login(@Valid @RequestBody LoginRequest request,
                                       HttpServletRequest httpRequest) {
        return Result.success(authService.login(request, IpUtils.getIp(httpRequest)));
    }
}