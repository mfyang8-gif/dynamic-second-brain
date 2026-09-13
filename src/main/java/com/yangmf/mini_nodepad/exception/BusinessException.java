package com.yangmf.mini_nodepad.exception;



import org.springframework.http.HttpStatus;

/**
 * 通用业务异常（默认 HTTP 400）
 * 替代原 UserException，统一承载所有业务逻辑类异常
 */
public class BusinessException extends MiniNotePadException {

    public BusinessException(String message) {
        super(message, HttpStatus.BAD_REQUEST);
    }

    public BusinessException(String message, Throwable cause) {
        super(message, HttpStatus.BAD_REQUEST, cause);
    }

    public BusinessException(String message, HttpStatus status) {
        super(message, status);
    }

    public BusinessException(String message, HttpStatus status, Throwable cause) {
        super(message, status, cause);
    }
}