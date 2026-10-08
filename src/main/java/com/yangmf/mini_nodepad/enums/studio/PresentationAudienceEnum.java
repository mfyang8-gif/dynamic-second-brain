package com.yangmf.mini_nodepad.enums.studio;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

@Getter
public enum PresentationAudienceEnum {
    GENERAL("GENERAL", "普通大众 - 通俗易懂"),
    TECHNICAL("TECHNICAL", "专业技术人员 - 深度硬核"),
    EXECUTIVE("EXECUTIVE", "高管/投资人 - 侧重结论与商业价值");

    @JsonValue
    private final String code;
    private final String description;

    PresentationAudienceEnum(String code, String description) {
        this.code = code;
        this.description = description;
    }
}