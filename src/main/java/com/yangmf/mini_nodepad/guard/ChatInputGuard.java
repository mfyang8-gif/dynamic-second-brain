package com.yangmf.mini_nodepad.guard;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class ChatInputGuard {

    private static final int MAX_QUESTION_LENGTH = 2000;
    private static final int MAX_CUSTOM_TEXT_LENGTH = 5000;

    public String sanitize(String question) {
        if (question == null) {
            return "";
        }
        String cleaned = question.replaceAll("[\\p{Cntrl}&&[^\r\n\t]]", "");
        if (cleaned.length() > MAX_QUESTION_LENGTH) {
            cleaned = cleaned.substring(0, MAX_QUESTION_LENGTH);
            log.debug("用户问题被截断至 {} 字符", MAX_QUESTION_LENGTH);
        }
        return cleaned.trim();
    }

    public String sanitizeCustomText(String customText) {
        if (customText == null) {
            return null;
        }
        String cleaned = customText.replaceAll("[\\p{Cntrl}&&[^\r\n\t]]", "");
        if (cleaned.length() > MAX_CUSTOM_TEXT_LENGTH) {
            cleaned = cleaned.substring(0, MAX_CUSTOM_TEXT_LENGTH);
            log.debug("自定义指令被截断至 {} 字符", MAX_CUSTOM_TEXT_LENGTH);
        }
        return cleaned.trim();
    }
}