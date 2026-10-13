// pojo/vo/StudioArtifactListVO.java
package com.yangmf.mini_nodepad.pojo.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.yangmf.mini_nodepad.enums.studio.ArtifactStatus;
import com.yangmf.mini_nodepad.enums.studio.ArtifactType;
import com.yangmf.mini_nodepad.pojo.entity.StudioArtifact;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 生成历史「列表行」VO。
 *
 * 与实体的三处刻意差异（都不是随手省略，是安全/IO 红线）：
 *
 * 1. 【没有 payload】—— 多态 JSON 大文本只在详情接口返回，
 *    列表查询在 SQL 层（.select(...)）就不取这一列，降低 DB IO 与带宽。
 * 2. 【没有 userId / deleted】—— userId 是他人越权探测面（脱敏屏蔽），
 *    deleted 是纯内部字段，前端永远不需要。
 * 3. 【时间已格式化】—— @JsonFormat 统一输出 yyyy-MM-dd HH:mm:ss，
 *    不把 Jackson 的默认序列化格式直接漏给前端。
 */
@Data
@Builder
@Schema(name = "生成历史列表项")
public class StudioArtifactListVO {

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

    @Schema(description = "AI 精修标题（展示优先级：dynamicTitle ?? title）")
    private String dynamicTitle;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @Schema(description = "创建时间", example = "2026-10-10 19:30:00")
    private LocalDateTime createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;

    /** 实体 → 列表 VO（字段白名单拷贝，想漏都漏不进大字段） */
    public static StudioArtifactListVO from(StudioArtifact entity) {
        return StudioArtifactListVO.builder()
                .id(entity.getId())
                .bookId(entity.getBookId())
                .type(entity.getType())
                .status(entity.getStatus())
                .title(entity.getTitle())
                .dynamicTitle(entity.getDynamicTitle())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}
