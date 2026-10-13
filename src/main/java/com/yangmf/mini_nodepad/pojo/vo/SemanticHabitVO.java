package com.yangmf.mini_nodepad.pojo.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 用户习惯展示 VO（缺口 1）：不暴露 user_id / fact_hash / deleted / updated_at 等内部字段（红线 4）。
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Schema(name = "用户全局习惯")
public class SemanticHabitVO {

    @Schema(description = "习惯ID（删除操作标识）")
    private Long id;

    @Schema(description = "习惯标准化短语")
    private String fact;

    @Schema(description = "规则类型: PREFERENCE-正向偏好 / CONSTRAINT-负向禁忌")
    private String ruleType;

    @Schema(description = "最近强化时间")
    private LocalDateTime lastReinforcedAt;
}
