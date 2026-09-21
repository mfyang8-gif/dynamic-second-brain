package com.yangmf.mini_nodepad.ai.component;

import com.yangmf.mini_nodepad.ai.aiservice.IntentRouter;
import com.yangmf.mini_nodepad.enums.IntentType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class IntentRouterService {

    private final IntentRouter intentRouter;

    private static final Set<String> CHITCHAT_EXACT = Set.of(
            "你好", "您好", "嗨", "hi", "hello", "hey",
            "谢谢", "感谢", "thanks", "thank you",
            "再见", "拜拜", "bye", "晚安", "早安",
            "嗯嗯", "好的", "ok", "okay",
            "哈哈", "呵呵", "嘻嘻", "嘿嘿",
            "在吗", "在不在", "你好呀", "hello呀",
            "早上好", "下午好", "晚上好"
    );

    public IntentType classify(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return IntentType.CHITCHAT;
        }

        String trimmed = userMessage.trim().toLowerCase();
        String stripped = trimmed.replaceAll("[！!。.？?~，,、]+$", "");

        if (CHITCHAT_EXACT.contains(trimmed) || CHITCHAT_EXACT.contains(stripped)) {
            log.info("意图快速路径 | intent=CHITCHAT, rule=exact_match, query={}", trimmed);
            return IntentType.CHITCHAT;
        }

        try {
            String result = intentRouter.classify(userMessage).trim().toUpperCase();
            String cleaned = result.replaceAll("[^A-Z_]", "");
            IntentType intent = IntentType.valueOf(cleaned);
            log.info("意图分类完成 | intent={}, query={}", intent, truncate(userMessage, 50));
            return intent;
        } catch (Exception e) {
            log.warn("意图分类失败，降级为 KNOWLEDGE_QUERY | query={}", truncate(userMessage, 50), e);
            return IntentType.KNOWLEDGE_QUERY;
        }
    }

    private String truncate(String text, int maxLen) {
        return text.length() > maxLen ? text.substring(0, maxLen) + "..." : text;
    }
}