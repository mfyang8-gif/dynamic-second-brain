// enums/studio/ArtifactType.java
package com.yangmf.mini_nodepad.enums.studio;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

@Getter
public enum ArtifactType {

    QUIZ("QUIZ", "测验"),
    FLASHCARD("FLASHCARD", "抽认卡"),
    MINDMAP("MINDMAP", "思维导图"),
    REPORT("REPORT", "报告"),
    PRESENTATION("PRESENTATION", "演示文稿");
    @EnumValue
    @JsonValue
    private final String code;
    private final String description;

    ArtifactType(String code, String description) {
        this.code = code;
        this.description = description;
    }
}