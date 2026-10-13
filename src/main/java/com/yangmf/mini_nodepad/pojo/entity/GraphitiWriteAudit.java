package com.yangmf.mini_nodepad.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Graphiti 写入终态审计（Phase 4 · B1）。
 *
 * <p>★ 本表<b>不是记忆源</b>，是「隔离 / 审计日志」—— 记录所有<b>永不（重）投递</b>的终态，
 * 因此不违反「L2 唯一真相源」铁律。
 *
 * <p>三条使用约束：
 * <ol>
 *   <li>只允许 {@code INSERT IGNORE}（{@code message_id} 有 UNIQUE 约束）——
 *       重复隔离同一条消息必须是幂等的，不能因 at-least-once 重复消费而抛唯一键冲突。</li>
 *   <li>写入即终态：此后该 {@code message_id} 会被 Confirm / Replay 两条 SQL 的
 *       {@code NOT EXISTS} 排除。</li>
 *   <li>人工恢复 = 删掉这一行（无需任何恢复工具）。</li>
 * </ol>
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@TableName("graphiti_write_audit")
@Schema(name = "Graphiti 写入终态审计")
public class GraphitiWriteAudit implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    @Schema(description = "自增主键")
    private Long id;

    /**
     * 归属用户。
     *
     * <p>⚠️ 与 {@code book.user_id} 同型（VARCHAR）：本工程用户主键在库中是字符串，
     * 只在调用 Graphiti 时才转成数值型。此处保留原样便于直接与业务表对账。
     */
    @Schema(description = "归属用户（冗余存储）")
    private String userId;

    @Schema(description = "chat_message.id")
    private String messageId;

    @Schema(description = "终态原因（枚举名）")
    private String reason;

    @Schema(description = "原文快照（层 4 抽样数据源）")
    private String contentSnapshot;

    /** 补充说明：HTTP 码、轮次等。⚠️ 禁止写入完整响应体或任何敏感数据。 */
    @Schema(description = "补充说明")
    private String detail;

    @Schema(description = "隔离时刻")
    private LocalDateTime createdAt;
}
