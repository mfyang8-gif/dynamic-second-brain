package com.yangmf.mini_nodepad.service.auth;

import com.yangmf.mini_nodepad.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 登录安全守卫：基于 Redis 记录失败次数与账号锁。
 * 连续失败 MAX_FAILS 次 → 锁定 LOCK_MINUTES 分钟。
 * Key 全部转小写以规避大小写导致的绕过（如 Admin vs admin）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LoginSecurityGuard {

    private final StringRedisTemplate stringRedisTemplate;

    private static final int MAX_FAILS = 5;
    private static final long WINDOW_MINUTES = 15;
    private static final long LOCK_MINUTES = 15;

    private static final String FAIL_KEY_PREFIX = "login:fail:";
    private static final String LOCK_KEY_PREFIX = "login:lock:";

    public void assertNotLocked(String account) {
        String lockKey = LOCK_KEY_PREFIX + normalize(account);
        Long ttl = stringRedisTemplate.getExpire(lockKey, TimeUnit.SECONDS);
        if (ttl != null && ttl > 0) {
            long remainMinutes = (ttl + 59) / 60;
            log.warn("登录被拒：账号已锁定 | account={}, remainMin={}", account, remainMinutes);
            throw new BusinessException("账号已被临时锁定，请 " + remainMinutes + " 分钟后再试");
        }
    }

    public void recordFailure(String account) {
        String failKey = FAIL_KEY_PREFIX + normalize(account);
        Long count = stringRedisTemplate.opsForValue().increment(failKey);
        if (count == null) {
            return;
        }
        if (count == 1) {
            stringRedisTemplate.expire(failKey, WINDOW_MINUTES, TimeUnit.MINUTES);
        }
        if (count >= MAX_FAILS) {
            String lockKey = LOCK_KEY_PREFIX + normalize(account);
            stringRedisTemplate.opsForValue().set(lockKey, "1", LOCK_MINUTES, TimeUnit.MINUTES);
            stringRedisTemplate.delete(failKey);
            log.warn("账号因连续 {} 次失败已锁定 {} 分钟 | account={}", MAX_FAILS, LOCK_MINUTES, account);
        }
    }

    public void clearFailures(String account) {
        stringRedisTemplate.delete(FAIL_KEY_PREFIX + normalize(account));
    }

    private String normalize(String account) {
        return account == null ? "" : account.toLowerCase();
    }
}