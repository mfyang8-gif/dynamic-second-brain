package com.yangmf.mini_nodepad.pojo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(name = "更新知识库请求")
public class BookUpdateDTO {

    @Size(max = 64, message = "书本名称不能超过64个字符")
    @Schema(description = "书本名称", maxLength = 64)
    private String name;

    @Size(max = 255, message = "描述不能超过255个字符")
    @Schema(description = "书本描述", maxLength = 255)
    private String description;
}