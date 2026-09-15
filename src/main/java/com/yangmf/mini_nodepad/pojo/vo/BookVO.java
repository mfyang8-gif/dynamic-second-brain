package com.yangmf.mini_nodepad.pojo.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "知识库详情")
public class BookVO {

    @Schema(description = "书本ID")
    private String id;

    @Schema(description = "书本名称")
    private String name;

    @Schema(description = "书本描述")
    private String description;

    @Schema(description = "知识页数量")
    private Long pageCount;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;
}