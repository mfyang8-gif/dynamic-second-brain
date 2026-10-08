// pojo/entity/StudioArtifact.java
package com.yangmf.mini_nodepad.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.yangmf.mini_nodepad.enums.studio.ArtifactStatus;
import com.yangmf.mini_nodepad.enums.studio.ArtifactType;
import com.yangmf.mini_nodepad.pojo.dto.studio.payload.ArtifactPayload;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@TableName(value = "studio_artifact", autoResultMap = true)
@Schema(name = "Studio衍生工件")
public class StudioArtifact implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_UUID)
    @Schema(description = "工件ID")
    private String id;

    @Schema(description = "所属用户ID")
    private String userId;

    @Schema(description = "关联知识库ID")
    private String bookId;

    @Schema(description = "工件类型")
    private ArtifactType type;

    @Schema(description = "异步状态")
    private ArtifactStatus status;

    @Schema(description = "工件标题")
    private String title;

    @TableField(typeHandler = JacksonTypeHandler.class)
    @Schema(description = "结构化JSON数据（多态）")
    private ArtifactPayload payload;

    @Schema(description = "失败原因")
    private String errorMsg;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;

    @TableLogic
    @Schema(description = "逻辑删除 0-正常 1-已删除")
    private Integer deleted;
}