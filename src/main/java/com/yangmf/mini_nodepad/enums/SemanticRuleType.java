package com.yangmf.mini_nodepad.enums;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 全局语义记忆规则类型：正向偏好 vs 负向禁忌。
 * DB 与 LLM 结构化输出统一使用大写 code 字符串；解析失败（白名单外）一律丢弃，
 * 防记忆投毒（盲区 G：LLM 吐非法 rule_type 直接拒收）。
 */
public enum SemanticRuleType {

    /** 正向偏好（如"我习惯用 Vue3"） */
    PREFERENCE("PREFERENCE"),

    /** 负向禁忌（如"绝不要写注释"） */
    CONSTRAINT("CONSTRAINT");

    private final String code;

    SemanticRuleType(String code) {
        this.code = code;
    }

    @JsonValue
    public String getCode() {
        return code;
    }

    /** 白名单解析：任何非法/未知值返回 null，由调用方丢弃该条提取结果 */
    public static SemanticRuleType parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = raw.trim().toUpperCase();
        for (SemanticRuleType type : values()) {
            if (type.code.equals(normalized)) {
                return type;
            }
        }
        return null;
    }
}
