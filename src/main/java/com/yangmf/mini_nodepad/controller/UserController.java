package com.yangmf.mini_nodepad.controller;

import com.yangmf.mini_nodepad.pojo.vo.UserVO;
import com.yangmf.mini_nodepad.properties.JwtProperties;
import com.yangmf.mini_nodepad.result.Result;
import com.yangmf.mini_nodepad.service.AuthService;
import com.yangmf.mini_nodepad.service.TokenService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/user")
@RequiredArgsConstructor
@Tag(name = "用户模块", description = "用户信息与登出（需携带 Token）")
public class UserController {

    private final AuthService authService;
    private final TokenService tokenService;
    private final JwtProperties jwtProperties;

    @GetMapping("/me")
    @Operation(summary = "获取当前用户信息")
    public Result<UserVO> getCurrentUser() {
        return Result.success(authService.getCurrentUser());
    }

    @PostMapping("/logout")
    @Operation(summary = "退出登录", description = "撤销当前 Token，使其立即失效")
    public Result<Void> logout(HttpServletRequest request) {
        String token = request.getHeader(jwtProperties.getUserTokenName());
        if (token != null && !token.isEmpty()) {
            tokenService.removeUserToken(token);
        }
        authService.logout();
        return Result.success();
    }

    @PutMapping("/profile")
    @Operation(summary = "修改个人资料")
    public Result<UserVO> updateProfile(@Valid @RequestBody UpdateProfileRequest req) {
        return Result.success(authService.updateProfile(req.getNickname(), req.getAvatarUrl()));
    }

    @Data
    public static class UpdateProfileRequest {
        @Size(max = 30, message = "昵称最长30个字符")
        private String nickname;
        private String avatarUrl;
    }
}