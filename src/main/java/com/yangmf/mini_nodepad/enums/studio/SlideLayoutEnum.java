package com.yangmf.mini_nodepad.enums.studio;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

@Getter
public enum SlideLayoutEnum {
    COVER("COVER", "封面页"),
    BULLET_LIST("BULLET_LIST", "常规图文列表页"),
    COMPARISON("COMPARISON", "双列对比页"),
    QUOTE("QUOTE", "金句/核心观点突出页");

    @JsonValue
    private final String code;
    private final String description;

    SlideLayoutEnum(String code, String description) {
        this.code = code;
        this.description = description;
    }
}