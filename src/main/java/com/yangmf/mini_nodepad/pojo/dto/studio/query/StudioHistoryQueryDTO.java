// pojo/dto/studio/query/StudioHistoryQueryDTO.java
package com.yangmf.mini_nodepad.pojo.dto.studio.query;

import com.yangmf.mini_nodepad.enums.studio.ArtifactType;
import com.yangmf.mini_nodepad.pojo.dto.PageQueryDTO;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 生成历史（StudioArtifact）分页查询入参。
 *
 * 红线：
 * 1. 【没有 userId 字段】——当前登录用户 ID 只允许从 BaseContext 取，
 *    绝不相信前端传来的任何身份参数（横向越权防线从入参模型就开始修）。
 * 2. page/pageSize 继承自 PageQueryDTO（@Min(1) / @Max(100)），
 *    业务层还会再兜一次上限，双重防御。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(name = "生成历史分页查询参数")
public class StudioHistoryQueryDTO extends PageQueryDTO {

    @Size(max = 64, message = "bookId 长度非法")
    @Schema(description = "按知识库过滤（不传 = 查当前用户全部历史）", example = "39f0aa780e004714a02c8263b6ef2b40")
    private String bookId;

    @Schema(description = "按工件类型过滤（不传 = 全部类型）", example = "MINDMAP")
    private ArtifactType type;
}
