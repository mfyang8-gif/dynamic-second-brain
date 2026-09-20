package com.yangmf.mini_nodepad.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.yangmf.mini_nodepad.enums.AiProcessStatusEnum;
import com.yangmf.mini_nodepad.enums.SourceTypeEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@Schema(name = "知识页")
@AllArgsConstructor
@NoArgsConstructor
public class Page implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_UUID)
    @Schema(description = "知识页ID")
    private String id;

    @Schema(description = "所属书本ID")
    private String bookId;

    @Schema(description = "标题")
    private String title;

    @Schema(description = "AI生成摘要")
    private String summary;

    @Schema(description = "正文内容")
    private String content;

    @Schema(description = "来源类型")
    private SourceTypeEnum sourceType;

    @Schema(description = "向量分块数量")
    private Integer chunkCount;

    @Schema(description = "是否开启AI自动清洗 0-关闭 1-开启")
    private Integer autoOptimize;

    @Schema(description = "AI处理状态")
    private AiProcessStatusEnum aiProcessStatus;

    @Schema(description = "AI处理结果/失败原因日志")
    private String aiProcessMsg;

    @Schema(description = "是否允许AI修改 0-不允许 1-允许")
    private Integer allowAiModify;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;

    @TableLogic
    @Schema(description = "逻辑删除标记 0-正常 1-已删除")
    private Integer deleted;

    @Schema(description = "删除时间")
    private LocalDateTime deletedAt;
}