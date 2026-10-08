package com.yangmf.mini_nodepad.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yangmf.mini_nodepad.context.BaseContext;
import com.yangmf.mini_nodepad.exception.BusinessException;
import com.yangmf.mini_nodepad.exception.ResourceNotFoundException;
import com.yangmf.mini_nodepad.mapper.UserMapper;
import com.yangmf.mini_nodepad.pojo.dto.auth.AuthResponse;
import com.yangmf.mini_nodepad.pojo.dto.auth.LoginRequest;
import com.yangmf.mini_nodepad.pojo.dto.auth.RegisterRequest;
import com.yangmf.mini_nodepad.pojo.entity.User;
import com.yangmf.mini_nodepad.pojo.vo.UserVO;
import com.yangmf.mini_nodepad.properties.JwtProperties;
import com.yangmf.mini_nodepad.service.AuthService;
import com.yangmf.mini_nodepad.service.TokenService;
import com.yangmf.mini_nodepad.service.auth.LoginSecurityGuard;
import com.yangmf.mini_nodepad.utils.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final JwtProperties jwtProperties;
    private final TokenService tokenService;
    private final LoginSecurityGuard loginSecurityGuard;

    @Override
    public AuthResponse register(RegisterRequest request, String clientIp) {
        // 预检：快速失败路径，节省绝大多数正常场景下的 DB 唯一索引冲突成本
        if (existsByUsername(request.getUsername())) {
            throw new BusinessException("用户名已被注册");
        }
        if (existsByEmail(request.getEmail())) {
            throw new BusinessException("邮箱已被注册");
        }

        User user = User.builder()
                .username(request.getUsername())
                .email(request.getEmail())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .nickname(request.getNickname() != null ? request.getNickname() : request.getUsername())
                .status(1)
                .build();

        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException e) {
            // 并发竞态兜底：依赖 DB 唯一索引 uk_username / uk_email 拦下脏数据，转成友好提示
            String msg = e.getMessage() == null ? "" : e.getMessage();
            log.warn("注册冲突 | username={}, email={}, ip={}, err={}",
                    request.getUsername(), request.getEmail(), clientIp, msg);
            if (msg.contains("uk_username")) {
                throw new BusinessException("用户名已被注册");
            }
            if (msg.contains("uk_email")) {
                throw new BusinessException("邮箱已被注册");
            }
            throw new BusinessException("账号信息冲突，请稍后重试");
        }

        log.info("新用户注册成功 | userId={}, username={}, ip={}",
                user.getId(), user.getUsername(), clientIp);
        return buildAuthResponse(user);
    }

    @Override
    public AuthResponse login(LoginRequest request, String clientIp) {
        String account = request.getAccount();

        // 1. 前置：账号锁检查（防暴力破解）
        loginSecurityGuard.assertNotLocked(account);

        // 2. 查用户：不区分"账号不存在"与"密码错误"的对外文案（防用户枚举）
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>()
                        .eq(User::getUsername, account)
                        .or()
                        .eq(User::getEmail, account)
        );

        if (user == null) {
            log.warn("登录失败 | account={}, ip={}, reason=账号不存在", account, clientIp);
            loginSecurityGuard.recordFailure(account);
            throw new BusinessException("用户名或密码错误");
        }

        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            log.warn("登录失败 | account={}, userId={}, ip={}, reason=密码错误",
                    account, user.getId(), clientIp);
            loginSecurityGuard.recordFailure(account);
            throw new BusinessException("用户名或密码错误");
        }

        if (user.getStatus() != null && user.getStatus() == 0) {
            log.warn("登录被拒 | account={}, userId={}, ip={}, reason=账号禁用",
                    account, user.getId(), clientIp);
            throw new BusinessException("账号已被禁用，请联系管理员");
        }

        // 3. 成功：清零失败计数
        loginSecurityGuard.clearFailures(account);
        log.info("用户登录成功 | userId={}, account={}, ip={}", user.getId(), account, clientIp);
        return buildAuthResponse(user);
    }

    @Override
    public UserVO getCurrentUser() {
        Long userId = BaseContext.getCurrentId();
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new ResourceNotFoundException("用户不存在");
        }
        return toVO(user);
    }

    @Override
    public void logout() {
        Long userId = BaseContext.getCurrentId();
        log.info("用户登出 | userId={}", userId);
    }

    @Override
    public UserVO updateProfile(String nickname, String avatarUrl) {
        Long userId = BaseContext.getCurrentId();
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new ResourceNotFoundException("用户不存在");
        }

        if (nickname != null) {
            user.setNickname(nickname);
        }
        if (avatarUrl != null) {
            user.setAvatarUrl(avatarUrl);
        }
        userMapper.updateById(user);

        log.info("用户资料已更新 | userId={}", userId);
        return toVO(user);
    }

    private AuthResponse buildAuthResponse(User user) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", user.getId());
        claims.put("username", user.getUsername());

        String token = jwtUtil.createJWT(
                jwtProperties.getUserSecretKey(),
                jwtProperties.getUserTtl(),
                claims
        );

        tokenService.saveUserToken(token, user.getId());

        return AuthResponse.builder()
                .token(token)
                .expiresIn(jwtProperties.getUserTtl() / 1000)
                .user(toVO(user))
                .build();
    }

    private UserVO toVO(User user) {
        return UserVO.builder()
                .id(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .nickname(user.getNickname())
                .avatarUrl(user.getAvatarUrl())
                .createdAt(user.getCreatedAt())
                .build();
    }

    private boolean existsByUsername(String username) {
        return userMapper.selectCount(
                new LambdaQueryWrapper<User>().eq(User::getUsername, username)
        ) > 0;
    }

    private boolean existsByEmail(String email) {
        return userMapper.selectCount(
                new LambdaQueryWrapper<User>().eq(User::getEmail, email)
        ) > 0;
    }
}