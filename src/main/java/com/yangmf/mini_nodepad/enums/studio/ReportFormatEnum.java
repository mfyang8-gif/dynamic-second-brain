package com.yangmf.mini_nodepad.enums.studio;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

@Getter
public enum ReportFormatEnum {
    DOCUMENT("DOCUMENT", "纯文本静态文档"),
    INTERACTIVE("INTERACTIVE", "互动式沉浸报告");

    @JsonValue
    private final String code;
    private final String description;

    ReportFormatEnum(String code, String description) {
        this.code = code;
        this.description = description;
    }
}