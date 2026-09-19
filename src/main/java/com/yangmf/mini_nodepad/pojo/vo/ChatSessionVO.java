package com.yangmf.mini_nodepad.pojo.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
@Schema(description = "会话列表项")
public class ChatSessionVO {

    @Schema(description = "会话ID")
    private String id;

    @Schema(description = "所属知识库ID")
    private String bookId;

    @Schema(description = "会话标题")
    private String title;

    @Schema(description = "最后更新时间")
    private LocalDateTime updatedAt;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;
}