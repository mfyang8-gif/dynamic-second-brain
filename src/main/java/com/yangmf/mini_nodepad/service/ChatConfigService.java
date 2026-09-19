package com.yangmf.mini_nodepad.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 对话配置映射服务
 * 将前端的角色/长度/自定义配置转为 System Prompt 文本片段
 * 纯 Java 映射，零 LLM 调用
 */
@Slf4j
@Service
public class ChatConfigService {

    private static final String DEFAULT_ROLE =
            "以专业研究助手的身份回复，保持客观、严谨、中立的学术语气。";

    private static final String LEARNING_GUIDE_ROLE =
            "你是一位耐心的学习导师。回复时请：\n" +
            "- 从基础概念讲起，循序渐进\n" +
            "- 多用类比和生活中的例子帮助理解\n" +
            "- 对专业术语必须附带简短解释\n" +
            "- 在关键节点设置引导性问题，激发思考";

    private static final String DEFAULT_LENGTH =
            "保持回答信息密度高且篇幅适中，确保覆盖核心要点即可。";

    private static final String LONGER_LENGTH =
            "请对每个论点展开深入分析：\n" +
            "- 每个核心观点至少用 2-3 段详细阐述\n" +
            "- 提供知识库中的具体细节、数据和例子作为支撑\n" +
            "- 多角度分析，不遗漏任何相关信息";

    private static final String SHORTER_LENGTH =
            "极度精简：\n" +
            "- 每个论点用 1-2 句话概括核心\n" +
            "- 只保留最关键的信息和结论\n" +
            "- 省略展开论述，直接给出要点";

    private static final String INJECTION_FALLBACK_NOTICE =
            "\n\n[系统提示：用户提交的自定义指令包含与核心规则冲突的内容，已自动回退为默认风格。]";

    private static final List<String> INJECTION_KEYWORDS = List.of(
            "忽略上面", "忽略以上", "忽略所有", "无视上述", "无视以上",
            "ignore previous", "ignore above", "ignore all",
            "disregard", "override", "覆盖指令", "取消限制",
            "不要引用", "不加引用", "禁止引用", "无需引用",
            "不要用知识库", "不要使用知识库", "用你自己的知识",
            "你现在是", "假装你是", "扮演"
    );

    /**
     * 解析角色指令
     *
     * @param role       "default" | "learning_guide" | "custom"
     * @param customText 自定义指令文本（role=custom 时使用）
     * @return 注入到 {{roleInstruction}} 的文本
     */
    public String resolveRoleInstruction(String role, String customText) {
        if (role == null || role.isBlank() || "default".equals(role)) {
            return DEFAULT_ROLE;
        }
        return switch (role) {
            case "learning_guide" -> LEARNING_GUIDE_ROLE;
            case "custom" -> resolveCustomRole(customText);
            default -> DEFAULT_ROLE;
        };
    }

    /**
     * 解析长度约束
     *
     * @param length "default" | "longer" | "shorter"
     * @return 注入到 {{lengthConstraint}} 的文本
     */
    public String resolveLengthConstraint(String length) {
        if (length == null || length.isBlank() || "default".equals(length)) {
            return DEFAULT_LENGTH;
        }
        return switch (length) {
            case "longer" -> LONGER_LENGTH;
            case "shorter" -> SHORTER_LENGTH;
            default -> DEFAULT_LENGTH;
        };
    }

    private String resolveCustomRole(String customText) {
        if (customText == null || customText.isBlank()) {
            return DEFAULT_ROLE;
        }
        if (isSuspicious(customText)) {
            log.warn("检测到疑似 Prompt 注入的自定义指令，已降级为默认角色 | input={}",
                    customText.length() > 100 ? customText.substring(0, 100) + "..." : customText);
            return DEFAULT_ROLE + INJECTION_FALLBACK_NOTICE;
        }
        return customText;
    }

    private boolean isSuspicious(String text) {
        String lower = text.toLowerCase();
        return INJECTION_KEYWORDS.stream().anyMatch(lower::contains);
    }
}