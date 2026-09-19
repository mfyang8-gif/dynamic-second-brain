package com.yangmf.mini_nodepad.pojo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Schema(description = "创建会话请求")
public class SessionCreateDTO {

    @NotBlank(message = "知识库ID不能为空")
    @Schema(description = "所属知识库ID")
    private String bookId;

    @Schema(description = "会话标题（可选，不传则默认'新对话'）")
    private String title;
}