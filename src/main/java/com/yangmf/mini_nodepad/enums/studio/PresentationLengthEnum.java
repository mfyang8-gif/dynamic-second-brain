package com.yangmf.mini_nodepad.enums.studio;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

@Getter
public enum PresentationLengthEnum {
    BRIEF("BRIEF", "短平快/约5-7页"),
    STANDARD("STANDARD", "标准/约10-15页"),
    COMPREHENSIVE("COMPREHENSIVE", "全面/20页以上");

    @JsonValue
    private final String code;
    private final String description;

    PresentationLengthEnum(String code, String description) {
        this.code = code;
        this.description = description;
    }
}