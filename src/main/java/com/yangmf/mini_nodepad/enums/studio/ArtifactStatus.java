// enums/studio/ArtifactStatus.java
package com.yangmf.mini_nodepad.enums.studio;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

@Getter
public enum ArtifactStatus {

    PENDING(0, "等待中"),
    PROCESSING(1, "生成中"),
    SUCCESS(2, "生成成功"),
    FAILED(3, "生成失败");

    @EnumValue
    @JsonValue
    private final int code;
    private final String description;

    ArtifactStatus(int code, String description) {
        this.code = code;
        this.description = description;
    }
}