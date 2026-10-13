package com.yangmf.mini_nodepad.pojo.dto;

import java.util.List;

/**
 * HabitExtractor 结构化输出的**信封对象**。
 * <p>
 * ⚠️ 为什么不能直接用 {@code List<ExtractedHabit>} 作返回类型：
 * LangChain4j 的 ServiceOutputParser 对 Collection 返回类型会选用
 * {@code PojoCollectionOutputParser}，而该类的 {@code formatInstructions()} 是
 * 「故意抛 IllegalStateException」的未实现方法（它只服务支持原生 JSON-Schema 的模型）。
 * community 的 QwenChatModel 不支持原生 schema，会回退到「把格式说明追加进提示词」路径，
 * 于是每次调用都在发请求之前抛 IllegalStateException: null，被 Consumer 的 catch 吞掉，
 * 表现为「候选行恒为 0」的静默失败。
 * POJO 返回类型走 {@code PojoOutputParser}，其 formatInstructions() 有完整实现。
 *
 * @param habits 本窗口提取出的候选习惯，无习惯时为**空列表**（不是 null）
 */
public record ExtractedHabitList(List<ExtractedHabit> habits) {
}
