/*
package com.yangmf.mini_nodepad.handler;


import com.yangmf.mini_nodepad.exception.*;
import com.yangmf.mini_nodepad.result.Result;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final String REQUEST_ID_ATTR = "requestId";

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Void> handleNoResourceFoundException(NoResourceFoundException e) {
        log.debug("静态资源未找到: {}", e.getMessage());
        return ResponseEntity.notFound().build();
    }
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Result<Void>> handleConstraintViolationException(ConstraintViolationException e, HttpServletRequest request) {
        StringBuilder msg = new StringBuilder("参数校验失败：");
        e.getConstraintViolations().forEach(violation -> {
            msg.append(violation.getPropertyPath()).append(" ").append(violation.getMessage()).append("; ");
        });
        log.warn("路径参数校验失败: {}", msg);
        return ResponseEntity.badRequest()
                .body(Result.error(400, msg.toString(), getRequestId(request)));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleMethodArgumentNotValidException(MethodArgumentNotValidException e, HttpServletRequest request) {
        StringBuilder msg = new StringBuilder("参数校验失败：");
        e.getBindingResult().getFieldErrors().forEach(error -> {
            msg.append(error.getField()).append(" ").append(error.getDefaultMessage()).append("; ");
        });
        log.warn("参数校验失败: {}", msg);
        return ResponseEntity.badRequest()
                .body(Result.error(400, msg.toString(), getRequestId(request)));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<Void>> handleHttpMessageNotReadableException(HttpMessageNotReadableException e, HttpServletRequest request) {
        log.warn("请求体解析失败: {}", e.getMessage());
        return ResponseEntity.badRequest()
                .body(Result.error(400, "请求体格式错误", getRequestId(request)));
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<Result<Void>> handleResourceNotFoundException(ResourceNotFoundException e, HttpServletRequest request) {
        log.warn("资源不存在: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Result.error(404, e.getMessage(), getRequestId(request)));
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<Result<Void>> handleUnauthorizedException(UnauthorizedException e, HttpServletRequest request) {
        log.warn("未授权访问: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Result.error(401, e.getMessage(), getRequestId(request)));
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<Result<Void>> handleForbiddenException(ForbiddenException e, HttpServletRequest request) {
        log.warn("禁止访问: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Result.error(403, e.getMessage(), getRequestId(request)));
    }

    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<Result<Void>> handleValidationException(ValidationException e, HttpServletRequest request) {
        log.warn("数据校验失败: {}", e.getMessage());
        return ResponseEntity.badRequest()
                .body(Result.error(400, e.getMessage(), getRequestId(request)));
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> handleBusinessException(BusinessException e, HttpServletRequest request) {
        log.warn("业务异常: {}", e.getMessage(), e);
        return ResponseEntity.status(e.getStatus())
                .body(Result.error(e.getStatus().value(), e.getMessage(),getRequestId(request)));
    }

    @ExceptionHandler(MiniNotePadException.class)
    public ResponseEntity<Result<Void>> handleMiniNotePadException(MiniNotePadException e, HttpServletRequest request) {
        log.warn("迷你记事本异常: {}", e.getMessage(), e);
        return ResponseEntity.status(e.getStatus())
                .body(Result.error(e.getStatus().value(), e.getMessage(), getRequestId(request)));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Result<Void>> handleRuntimeException(RuntimeException e, HttpServletRequest request) {
        log.error("运行时异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.error(500, "系统繁忙，请稍后再试", getRequestId(request)));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleException(Exception e, HttpServletRequest request) {
        log.error("系统异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.error(500, "系统发生错误，请联系管理员",getRequestId(request)));
    }

    private String getRequestId(HttpServletRequest request) {
        return (String) request.getAttribute(REQUEST_ID_ATTR);
    }
}*/
