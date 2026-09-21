package com.yangmf.mini_nodepad.ai.component;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class ChatInputGuard {

    @Value("${guard.max-question-length:2000}")
    private int maxQuestionLength;

    @Value("${guard.max-custom-text-length:5000}")
    private int maxCustomTextLength;

    public String sanitize(String question) {
        if (question == null) {
            return "";
        }
        String cleaned = question.replaceAll("[\\p{Cntrl}&&[^\r\n\t]]", "");
        if (cleaned.length() > maxQuestionLength) {
            cleaned = cleaned.substring(0, maxQuestionLength);
            log.debug("用户问题被截断 | maxLength={}", maxQuestionLength);
        }
        return cleaned.trim();
    }

    public String sanitizeCustomText(String customText) {
        if (customText == null) {
            return null;
        }
        String cleaned = customText.replaceAll("[\\p{Cntrl}&&[^\r\n\t]]", "");
        if (cleaned.length() > maxCustomTextLength) {
            cleaned = cleaned.substring(0, maxCustomTextLength);
            log.debug("自定义指令被截断 | maxLength={}", maxCustomTextLength);
        }
        return cleaned.trim();
    }
}