package com.yangmf.mini_nodepad.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yangmf.mini_nodepad.enums.SemanticRuleType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 习惯候选池：跨会话频次晋升的计数载体（内部表，字段不进任何 VO）。
 * promoted / last_hit_at 为晋升与 TTL 懒清理的内部控制位。
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@TableName("user_semantic_candidate")
@Schema(name = "习惯候选池")
public class UserSemanticCandidate implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    @Schema(description = "所属用户ID")
    private Long userId;

    @Schema(description = "规范化 fact 的 SHA-256，与 user_id 组联合唯一索引")
    private String factHash;

    @Schema(description = "习惯标准化短语（消毒后）")
    private String fact;

    @Schema(description = "规则类型: PREFERENCE | CONSTRAINT")
    private SemanticRuleType ruleType;

    @Schema(description = "独立会话窗口命中次数（单窗口批内去重后最多 +1）")
    private Integer hitCount;

    @Schema(description = "晋升标记 0-候选 1-已晋升")
    private Integer promoted;

    @Schema(description = "最近一次命中时间，30 天未再命中由 TTL 懒清理")
    private LocalDateTime lastHitAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
