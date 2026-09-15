package com.yangmf.mini_nodepad.pojo.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Schema(name = "知识库/笔记本")
public class Book implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "书本ID")
    private String id;

    @Schema(description = "所属用户ID")
    private String userId;

    @Schema(description = "书本名称")
    private String name;

    @Schema(description = "书本描述")
    private String description;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "逻辑删除标记 0-正常 1-已删除")
    private Integer deleted;

    @Schema(description = "删除时间")
    private LocalDateTime deletedAt;
}