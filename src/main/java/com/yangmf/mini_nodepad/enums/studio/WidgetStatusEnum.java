package com.yangmf.mini_nodepad.enums.studio;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

@Getter
public enum WidgetStatusEnum {
    RECOMMENDED("RECOMMENDED", "默认推荐态"),
    DISMISSED("DISMISSED", "已关闭"),
    GENERATING("GENERATING", "生成中"),
    GENERATED_SHOW("GENERATED_SHOW", "已生成并展开"),
    GENERATED_HIDE("GENERATED_HIDE", "已生成但收起");

    @JsonValue
    private final String code;
    private final String description;

    WidgetStatusEnum(String code, String description) {
        this.code = code;
        this.description = description;
    }
}