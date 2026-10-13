package com.yangmf.mini_nodepad.pojo.dto;

/**
 * 实质判定结果（{@code MemorySignalJudge} 的输出契约）。
 *
 * <p>输出必须与判定器的 prompt 严格对应：{@code {"shouldRemember": bool, "reason": "..."}}。
 *
 * <p>⚠️ 返回值属<b>不可信数据</b>（模型产出）：调用方只允许使用 {@code shouldRemember}，
 * 且必须对 {@code null} 做兜底（解析失败时按「判否」处理并记日志，
 * 绝不能让判定异常变成「整批投递失败」）。
 *
 * @param shouldRemember 是否值得沉淀为长期记忆（true = 投递；false = 终态隔离）
 * @param reason         判定理由（仅用于日志与审计快照，不参与任何逻辑分支）
 */
public record MemorySignalVerdict(boolean shouldRemember, String reason) {
}
