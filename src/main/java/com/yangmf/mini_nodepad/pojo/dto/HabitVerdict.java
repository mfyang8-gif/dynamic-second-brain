package com.yangmf.mini_nodepad.pojo.dto;

/**
 * HabitJudge 结构化输出：新习惯与历史候选中某一条是否「事实同一」。
 * <p>
 * ⚠️ 与 {@link ExtractedHabitList} 同理，返回类型必须是 POJO 而不是集合/基本类型包装：
 * LangChain4j 的 ServiceOutputParser 对 Collection 返回类型会选用
 * {@code PojoCollectionOutputParser}，其 {@code formatInstructions()} 故意抛
 * IllegalStateException，而 community 的 QwenChatModel 不支持原生 JSON-Schema —— 必炸。
 * <p>
 * isSame 用包装类型 Boolean 而非 boolean：模型漏字段时得到 null，
 * 调用方可据此走「解析失败 → 按不相同处理」的安全分支，而不是被默认值 false 掩盖。
 *
 * @param isSame      是否为同一事实的同义复述（true = 相同）
 * @param matchedHash 判定相同时返回候选那条的 fact_hash；不相同时返回 null 或空串
 */
public record HabitVerdict(Boolean isSame, String matchedHash) {
}
