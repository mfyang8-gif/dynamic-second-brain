package com.yangmf.mini_nodepad.pojo.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@Schema(name = "知识页详情")
@NoArgsConstructor
@AllArgsConstructor
public class PageVO {

    @Schema(description = "知识页ID")
    private String id;

    @Schema(description = "所属书本ID")
    private String bookId;

    @Schema(description = "标题")
    private String title;

    @Schema(description = "AI生成摘要")
    private String summary;

    @Schema(description = "来源类型")
    private String sourceType;

    @Schema(description = "是否开启AI自动清洗 0-关闭 1-开启")
    private Integer autoOptimize;

    @Schema(description = "是否允许AI修改 0-不允许 1-允许")
    private Integer allowAiModify;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;

    @Schema(description = "删除时间（仅回收站数据有值）")
    private LocalDateTime deletedAt;


    @Schema(description = "AI处理状态: 0-待处理/不处理, 1-处理中, 2-成功, 3-失败, 4-部分成功(降级)")
    private Integer aiProcessStatus;

    @Schema(description = "AI处理结果/失败原因日志")
    private String aiProcessMsg;
}