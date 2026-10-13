package com.yangmf.mini_nodepad.pojo.dto;

/**
 * HabitExtractor 结构化输出单元：LLM 从对话窗口中提取的一条候选习惯。
 * fact/ruleType 均为不可信输入，入库前必须经 PromptSanitizer 消毒 + SemanticRuleType 白名单过滤。
 *
 * @param fact     标准化习惯短语（如"用户偏好用 Vue3 开发前端"）
 * @param ruleType PREFERENCE | CONSTRAINT（原始字符串，解析走白名单）
 */
public record ExtractedHabit(String fact, String ruleType) {
}
