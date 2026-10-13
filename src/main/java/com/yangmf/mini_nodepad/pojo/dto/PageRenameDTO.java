package com.yangmf.mini_nodepad.pojo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(name = "知识页重命名请求")
public class PageRenameDTO {

    @NotBlank(message = "标题不能为空")
    @Size(max = 128, message = "标题不能超过128个字符")
    @Schema(description = "知识页新标题", requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 128)
    private String title;
}