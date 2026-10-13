package com.yangmf.mini_nodepad.ai.component;

import org.springframework.stereotype.Component;

/**
 * Prompt 注入公共消毒器（记忆投毒三道闸的收口组件，盲区 G）。
 * 语义记忆链路的 fact 会被永久注入 System Prompt，其危害等级高于单次输入，
 * 落库前与注入组装时都必须经过本组件处理：
 *   1. 剥控制字符（防提示词结构破坏）；
 *   2. 剔除 &lt;thinking&gt; 段与系统保留 XML 标签（防提前闭合导致提示词塌陷）；
 *   3. 折叠空白 + 限长，超限直接拒收（防记忆库灌入长文稀释 Prompt）。
 */
@Component
public class PromptSanitizer {

    /** 单条 fact 最大长度，超限视为投毒载荷直接丢弃 */
    public static final int MAX_FACT_LENGTH = 200;

    /** 系统 Prompt 管线保留标签（与 CoreChatPrompt / regenerate 注入块同名即塌陷风险） */
    private static final String RESERVED_TAGS =
            "(thinking|correction_instruction|regeneration_rule|rejected_version"
                    + "|user_preferences|forbidden_rules|context|historySummary)";

    /**
     * 消毒 fact：合法返回净化文本，非法/超限/净化后为空返回 null（调用方丢弃该条）。
     */
    public String sanitizeFact(String fact) {
        if (fact == null || fact.isBlank()) {
            return null;
        }
        String cleaned = fact
                .replaceAll("[\\p{Cntrl}]", " ")
                .replaceAll("(?s)<" + RESERVED_TAGS + ">.*?</" + RESERVED_TAGS + ">", " ")
                .replaceAll("(?s)</?" + RESERVED_TAGS + ">", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (cleaned.isEmpty() || cleaned.length() > MAX_FACT_LENGTH) {
            return null;
        }
        return cleaned;
    }
}
