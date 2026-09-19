package com.yangmf.mini_nodepad.service;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
@Slf4j
@AllArgsConstructor
public class TokenService {


    private StringRedisTemplate stringRedisTemplate;



    private static final String USER_TOKEN_PREFIX = "user:token:";
    private static final String ADMIN_TOKEN_PREFIX = "admin:token:";
    private static final long TOKEN_EXPIRE_HOURS = 1;

    public void saveUserToken(String token, Long userId) {
        String key = USER_TOKEN_PREFIX + token;
        stringRedisTemplate.opsForValue().set(key, userId.toString(), TOKEN_EXPIRE_HOURS, TimeUnit.HOURS);
        log.debug("保存用户 Token: userId={}", userId);
    }

    public void saveAdminToken(String token, Long adminId) {
        String key = ADMIN_TOKEN_PREFIX + token;
        stringRedisTemplate.opsForValue().set(key, adminId.toString(), TOKEN_EXPIRE_HOURS, TimeUnit.HOURS);
        log.debug("保存管理员 Token: adminId={}", adminId);
    }

    public Long validateUserToken(String token) {
        String key = USER_TOKEN_PREFIX + token;
        String userIdStr = stringRedisTemplate.opsForValue().get(key);
        
        if (userIdStr == null) {
            return null;
        }
        
        try {
            return Long.parseLong(userIdStr);
        } catch (NumberFormatException e) {
            log.error("Token 对应的用户 ID 格式错误: token={}", token);
            return null;
        }
    }

    public Long validateAdminToken(String token) {
        String key = ADMIN_TOKEN_PREFIX + token;
        String adminIdStr = stringRedisTemplate.opsForValue().get(key);
        
        if (adminIdStr == null) {
            return null;
        }
        
        try {
            return Long.parseLong(adminIdStr);
        } catch (NumberFormatException e) {
            log.error("Token 对应的管理员 ID 格式错误: token={}", token);
            return null;
        }
    }

    public void removeUserToken(String token) {
        String key = USER_TOKEN_PREFIX + token;
        Boolean deleted = stringRedisTemplate.delete(key);
        log.debug("删除用户 Token: deleted={}", deleted);
    }

    public void removeAdminToken(String token) {
        String key = ADMIN_TOKEN_PREFIX + token;
        Boolean deleted = stringRedisTemplate.delete(key);
        log.debug("删除管理员 Token: deleted={}", deleted);
    }

    public void refreshToken(String token) {
        String userKey = USER_TOKEN_PREFIX + token;
        String adminKey = ADMIN_TOKEN_PREFIX + token;
        
        Boolean userExists = stringRedisTemplate.hasKey(userKey);
        if (Boolean.TRUE.equals(userExists)) {
            stringRedisTemplate.expire(userKey, TOKEN_EXPIRE_HOURS, TimeUnit.HOURS);
            return;
        }
        
        Boolean adminExists = stringRedisTemplate.hasKey(adminKey);
        if (Boolean.TRUE.equals(adminExists)) {
            stringRedisTemplate.expire(adminKey, TOKEN_EXPIRE_HOURS, TimeUnit.HOURS);
        }
    }
}
