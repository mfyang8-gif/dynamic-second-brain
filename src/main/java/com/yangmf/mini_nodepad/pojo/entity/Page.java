package com.yangmf.mini_nodepad.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
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

    @TableId(type = IdType.ASSIGN_UUID) // 强制 MP 使用 UUID 策略，与 Qdrant 保持绝对一致
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

    @Schema(description = "Qdrant向量点ID")
    private String qdrantPointId;

    // ================== AI 流水线状态控制机 ==================
    @Schema(description = "是否开启AI自动清洗 0-关闭 1-开启")
    private Integer autoOptimize;

    @Schema(description = "AI处理状态: 0-待处理/不处理, 1-处理中, 2-成功, 3-失败")
    private Integer aiProcessStatus;

    @Schema(description = "AI处理结果/失败原因日志")
    private String aiProcessMsg;


    @Schema(description = "是否允许AI修改 0-不允许 1-允许")
    private Integer allowAiModify;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;

    //  必须加上 @TableLogic，否则 MP 的 deleteById 会变成物理删除！
    @TableLogic
    @Schema(description = "逻辑删除标记 0-正常 1-已删除")
    private Integer deleted;

    @Schema(description = "删除时间")
    private LocalDateTime deletedAt;
}