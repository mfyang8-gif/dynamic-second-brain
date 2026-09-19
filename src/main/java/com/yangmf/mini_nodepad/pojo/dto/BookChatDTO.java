package com.yangmf.mini_nodepad.pojo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "知识库对话请求")
public class BookChatDTO {

    @NotBlank(message = "会话ID不能为空")
    @Schema(description = "对话会话ID")
    private String sessionId;

    @NotBlank(message = "问题内容不能为空")
    @Size(max = 2000, message = "问题长度不能超过2000字符")
    @Schema(description = "用户提问")
    private String question;

    @Schema(description = "指定笔记ID列表（可选，为空则搜索整个知识库）")
    private List<String> pageIds;

    @Schema(description = "对话角色: default | learning_guide | custom")
    private String role;

    @Schema(description = "回答长度: default | longer | shorter")
    private String length;

    @Size(max = 5000, message = "自定义指令长度不能超过5000字符")
    @Schema(description = "自定义角色指令（role=custom 时使用）")
    private String customText;
}