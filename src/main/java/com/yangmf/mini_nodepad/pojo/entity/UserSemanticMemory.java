package com.yangmf.mini_nodepad.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
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
 * 正式语义记忆库：晋升后的用户全局习惯，每次流式对话注入 System Prompt。
 * uk_user_fact(user_id, fact_hash) 阻断并发重复晋升；deleted 为"用户遗忘权"通道
 * （逻辑删除后不自动复活，防已删习惯被后台扫描悄悄加回）。
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@TableName("user_semantic_memory")
@Schema(name = "用户全局语义记忆")
public class UserSemanticMemory implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    @Schema(description = "所属用户ID")
    private Long userId;

    @Schema(description = "fact 的 SHA-256，与 user_id 组联合唯一索引")
    private String factHash;

    @Schema(description = "习惯标准化短语")
    private String fact;

    @Schema(description = "规则类型: PREFERENCE | CONSTRAINT")
    private SemanticRuleType ruleType;

    @Schema(description = "最近强化时间（候选再次命中时刷新，遗忘衰减依据）")
    private LocalDateTime lastReinforcedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @TableLogic
    @Schema(description = "逻辑删除标记 0-正常 1-已删除")
    private Integer deleted;
}
