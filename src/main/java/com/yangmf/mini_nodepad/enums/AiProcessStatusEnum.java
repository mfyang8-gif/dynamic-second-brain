package com.yangmf.mini_nodepad.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;

@Getter
@Schema(description = "AI处理状态枚举")
public enum AiProcessStatusEnum {

    PENDING(0, "待处理/不处理"),
    PROCESSING(1, "处理中"),
    SUCCESS(2, "成功"),
    FAILED(3, "失败"),
    DEGRADED(4, "部分成功（降级）");

    @EnumValue
    @JsonValue
    private final int code;
    private final String description;

    AiProcessStatusEnum(int code, String description) {
        this.code = code;
        this.description = description;
    }
}