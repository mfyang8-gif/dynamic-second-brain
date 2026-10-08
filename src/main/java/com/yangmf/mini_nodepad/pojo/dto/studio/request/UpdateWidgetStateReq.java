package com.yangmf.mini_nodepad.pojo.dto.studio.request;

import com.yangmf.mini_nodepad.enums.studio.WidgetStatusEnum;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class UpdateWidgetStateReq {

    @NotNull(message = "status 不能为空")
    private WidgetStatusEnum status;

    private String targetArtifactId;
}