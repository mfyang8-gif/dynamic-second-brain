package com.yangmf.mini_nodepad.utils;

import com.yangmf.mini_nodepad.aiservice.GeneralAssistant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 企业级文本清洗处理流水线
 * 包含：物理极速清洗 (正则) + 语义深度清洗 (LLM)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TextProcessManager {

    private final GeneralAssistant generalAssistant;

    /**
     * 核心公开方法：全自动清洗流水线
     *
     * @param rawText 原始杂乱文本
     * @return 清洗后的干净文本
     */
    public String process(String rawText) {
        if (rawText == null || rawText.trim().isEmpty()) {
            return "";
        }

        // 第一步：物理清洗 (毫秒级，兜底保障)
        String physicalCleaned = physicalClean(rawText);

        // 防御性拦截 1：如果洗完之后文本太短，没必要浪费 AI Token 去优化，直接返回
        if (physicalCleaned.length() < 10) {
            return physicalCleaned;
        }

        // 防御性拦截 2：防止超大文本直接把大模型上下文撑爆报错，截取前 5000 字
        String safeContent = physicalCleaned.length() > 5000 
                ? physicalCleaned.substring(0, 5000) 
                : physicalCleaned;

        // 第二步：AI 语义清洗
        try {
            return generalAssistant.optimizeText(safeContent);
        } catch (Exception e) {
            // 💡 架构师精髓：优雅降级 (Fallback)
            // 如果大模型网络超时、欠费或者宕机，绝对不能把异常抛给前端导致入库失败！
            // 记录一条警告日志，然后默默把第一步物理清洗的结果返回，保证系统依然可用。
            log.warn("AI 文本语义清洗失败，已自动降级为物理清洗结果。文本长度: {}", safeContent.length(), e);
            return physicalCleaned;
        }
    }

    /**
     * 内部纯物理规则清洗 (无状态，纯内存操作)
     */
    public String physicalClean(String rawText) {
        // 1. 压缩多余的换行符（把 3个以上的连续换行，压缩成最多 2个）
        String cleaned = rawText.replaceAll("\\n{3,}", "\n\n");
        
        // 2. 清除 Markdown 的分割线（例如 ---, ***）
        cleaned = cleaned.replaceAll("(?m)^[-*]{3,}\\s*$", "");
        
        // 3. 清除所有的 Emoji 表情 (提高向量纯度)
        cleaned = cleaned.replaceAll("[\\x{10000}-\\x{10FFFF}]", "");
        
        // 4. 清除行首和行尾的空白字符
        return cleaned.trim();
    }
}