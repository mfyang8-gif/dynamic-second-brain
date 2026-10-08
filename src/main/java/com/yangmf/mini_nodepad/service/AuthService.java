package com.yangmf.mini_nodepad.service;

import com.yangmf.mini_nodepad.pojo.dto.auth.AuthResponse;
import com.yangmf.mini_nodepad.pojo.dto.auth.LoginRequest;
import com.yangmf.mini_nodepad.pojo.dto.auth.RegisterRequest;
import com.yangmf.mini_nodepad.pojo.vo.UserVO;

public interface AuthService {

    AuthResponse register(RegisterRequest request, String clientIp);

    AuthResponse login(LoginRequest request, String clientIp);

    UserVO getCurrentUser();

    void logout();

    UserVO updateProfile(String nickname, String avatarUrl);
}