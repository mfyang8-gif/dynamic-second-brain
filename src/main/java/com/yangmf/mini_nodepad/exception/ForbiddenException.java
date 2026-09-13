package com.yangmf.mini_nodepad.exception;

import org.springframework.http.HttpStatus;

public class ForbiddenException extends MiniNotePadException {

    public ForbiddenException(String message) {
        super(message, HttpStatus.FORBIDDEN);
    }

    public ForbiddenException(String message, Throwable cause) {
        super(message, HttpStatus.FORBIDDEN, cause);
    }

    public ForbiddenException() {
        super("权限不足", HttpStatus.FORBIDDEN);
    }
}