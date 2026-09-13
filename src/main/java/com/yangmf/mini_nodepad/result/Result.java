package com.yangmf.mini_nodepad.result;


import com.yangmf.mini_nodepad.enums.ResultCodeEnum;
import lombok.Data;

import java.io.Serializable;

@Data
public class Result<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    private Integer code;
    private String msg;
    private T data;
    private Long timestamp;
    private String requestId;

    /**
     * 最常用的成功响应 (无数据)
     */
    public static <T> Result<T> success() {
        return build(ResultCodeEnum.SUCCESS, null);
    }

    /**
     * 最常用的成功响应 (带数据)
     */
    public static <T> Result<T> success(T data) {
        return build(ResultCodeEnum.SUCCESS, data);
    }

    /**
     * 默认的系统内部错误响应
     */
    public static <T> Result<T> error(String msg) {
        Result<T> result = build(ResultCodeEnum.INTERNAL_ERROR, null);
        result.setMsg(msg); // 覆盖枚举里的默认msg，返回具体的错误信息
        return result;
    }

    /**
     * 根据枚举构建响应 (核心方法)
     */
    public static <T> Result<T> build(ResultCodeEnum resultCodeEnum, T data) {
        Result<T> result = new Result<>();
        result.code = resultCodeEnum.getCode();
        result.msg = resultCodeEnum.getMsg();
        result.data = data;
        result.timestamp = System.currentTimeMillis();
        return result;
    }

    /**
     * 根据枚举构建响应，但支持自定义错误信息 (适用于抛出全局异常时)
     */
    public static <T> Result<T> build(ResultCodeEnum resultCodeEnum, String customMsg) {
        Result<T> result = build(resultCodeEnum, null);
        result.setMsg(customMsg);
        return result;
    }

    /**
     * 链式调用设置 requestId
     */
    public Result<T> requestId(String requestId) {
        this.requestId = requestId;
        return this;
    }

    public static <T> Result<T> error(int code, String msg, String requestId) {
        Result<T> result = new Result<>();
        result.setCode(code);
        result.setMsg(msg);
        result.setRequestId(requestId);
        result.setTimestamp(System.currentTimeMillis());
        return result;
    }

    public static <T> Result<T> error(int code, String msg) {
        Result<T> result = new Result<>();
        result.setCode(code);
        result.setMsg(msg);
        result.setTimestamp(System.currentTimeMillis());
        return result;
    }

}