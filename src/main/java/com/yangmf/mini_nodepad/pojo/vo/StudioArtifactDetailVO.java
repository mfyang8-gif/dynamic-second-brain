// pojo/vo/StudioArtifactDetailVO.java
package com.yangmf.mini_nodepad.pojo.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.yangmf.mini_nodepad.enums.studio.ArtifactStatus;
import com.yangmf.mini_nodepad.enums.studio.ArtifactType;
import com.yangmf.mini_nodepad.pojo.dto.studio.payload.ArtifactPayload;
import com.yangmf.mini_nodepad.pojo.entity.StudioArtifact;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 生成历史「详情」VO。
 *
 * 只有详情接口允许返回 payload 大字段（多态 JSON 本体）；
 * userId / deleted 依旧屏蔽 —— 身份与内部字段任何接口都不外露。
 */
@Data
@Builder
@Schema(name = "生成历史详情")
public class StudioArtifactDetailVO {

    @Schema(description = "工件ID")
    private String id;

    @Schema(description = "所属知识库ID")
    private String bookId;

    @Schema(description = "工件类型")
    private ArtifactType type;

    @Schema(description = "异步状态：0排队 1生成中 2完成 3失败")
    private ArtifactStatus status;

    @Schema(description = "兜底标题（用户提交的 topic）")
    private String title;

    @Schema(description = "AI 精修标题")
    private String dynamicTitle;

    @Schema(description = "结构化 JSON 本体（仅详情接口返回）")
    private ArtifactPayload payload;

    @Schema(description = "失败原因（成功时为 null）")
    private String errorMsg;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;

    /** 实体 → 详情 VO */
    public static StudioArtifactDetailVO from(StudioArtifact entity) {
        return StudioArtifactDetailVO.builder()
                .id(entity.getId())
                .bookId(entity.getBookId())
                .type(entity.getType())
                .status(entity.getStatus())
                .title(entity.getTitle())
                .dynamicTitle(entity.getDynamicTitle())
                .payload(entity.getPayload())
                .errorMsg(entity.getErrorMsg())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}
