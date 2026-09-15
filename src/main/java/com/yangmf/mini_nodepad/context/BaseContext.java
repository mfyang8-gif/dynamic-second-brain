package com.yangmf.mini_nodepad.context;

import com.yangmf.mini_nodepad.exception.UnauthorizedException;

public final class BaseContext {

    private static final ThreadLocal<Long> CURRENT_USER_ID = new ThreadLocal<>();

    private BaseContext() {}

    public static void setCurrentId(Long id) {
        CURRENT_USER_ID.set(id);
    }

    /**
     * 获取当前用户ID，未登录直接抛 401 异常
     */
    public static Long getCurrentId() {
        Long id = CURRENT_USER_ID.get();
        if (id == null) {
            throw new UnauthorizedException("未登录或登录已过期");
        }
        return id;
    }

    /**
     * 获取当前用户ID（允许返回 null，用于非强制登录场景）
     */
    public static Long getCurrentIdOrNull() {
        return CURRENT_USER_ID.get();
    }

    public static void removeCurrentId() {
        CURRENT_USER_ID.remove();
    }
}