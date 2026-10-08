package com.yangmf.mini_nodepad.enums.studio;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

@Getter
public enum PresentationVisualStyleEnum {
    BUSINESS("BUSINESS", "商务严谨"),
    ACADEMIC("ACADEMIC", "学术研究"),
    MINIMALIST("MINIMALIST", "极简大字报");

    @JsonValue
    private final String code;
    private final String description;

    PresentationVisualStyleEnum(String code, String description) {
        this.code = code;
        this.description = description;
    }
}