package com.yangmf.mini_nodepad.enums;

public enum ResultCodeEnum {
    SUCCESS(200, "操作成功"),
    BAD_REQUEST(400, "请求参数错误"),
    UNAUTHORIZED(401, "未授权或登录已过期"),
    FORBIDDEN(403, "无权限访问"),
    NOT_FOUND(404, "请求的资源不存在"),
    INTERNAL_ERROR(500, "系统内部错误"),

    // 你以后可以在这里随时增加业务报错，比如：
    // API_LIMIT_EXCEEDED(4001, "接口调用次数超限"),
    // AI_GENERATE_FAILED(5001, "AI生成摘要失败");
    ;

    private final Integer code;
    private final String msg;

    ResultCodeEnum(Integer code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    public Integer getCode() {
        return code;
    }

    public String getMsg() {
        return msg;
    }
}