package com.yangmf.mini_nodepad.enums.studio;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

@Getter
public enum WidgetTypeEnum {
    QUIZ("QUIZ", "测验"),
    MINDMAP("MINDMAP", "思维导图"),
    FLASHCARD("FLASHCARD", "抽认卡");

    @JsonValue
    private final String code;
    private final String description;

    WidgetTypeEnum(String code, String description) {
        this.code = code;
        this.description = description;
    }
}