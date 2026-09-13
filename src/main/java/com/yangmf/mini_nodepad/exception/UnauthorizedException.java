package com.yangmf.mini_nodepad.exception;

import org.springframework.http.HttpStatus;

public class UnauthorizedException extends MiniNotePadException {

    public UnauthorizedException(String message) {
        super(message, HttpStatus.UNAUTHORIZED);
    }

    public UnauthorizedException(String message, Throwable cause) {
        super(message, HttpStatus.UNAUTHORIZED, cause);
    }

    public UnauthorizedException() {
        super("未授权，请登录", HttpStatus.UNAUTHORIZED);
    }
}