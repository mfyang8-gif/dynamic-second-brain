package com.yangmf.mini_nodepad.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public class MiniNotePadException extends RuntimeException {

    private final HttpStatus status;

    public MiniNotePadException(String message) {
        super(message);
        this.status = HttpStatus.INTERNAL_SERVER_ERROR;
    }

    public MiniNotePadException(String message, HttpStatus status) {
        super(message);
        this.status = status;
    }

    public MiniNotePadException(String message, Throwable cause) {
        super(message, cause);
        this.status = HttpStatus.INTERNAL_SERVER_ERROR;
    }

    public MiniNotePadException(String message, HttpStatus status, Throwable cause) {
        super(message, cause);
        this.status = status;
    }
}