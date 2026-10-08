// ai/component/SuggestionService.java
package com.yangmf.mini_nodepad.ai.component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yangmf.mini_nodepad.ai.aiservice.SuggestionAssistant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class SuggestionService {

    private final SuggestionAssistant suggestionAssistant;
    private final ObjectMapper objectMapper;

    @Value("${chat.suggestion.max-answer-length:500}")
    private int maxAnswerLength;

    public List<String> generate(String question, String fullAnswer) {
        try {
            String truncatedAnswer = fullAnswer.length() > maxAnswerLength
                    ? fullAnswer.substring(0, maxAnswerLength)
                    : fullAnswer;

            String rawJson = suggestionAssistant.generateSuggestions(question, truncatedAnswer);
            if (rawJson == null || rawJson.isBlank()) {
                return Collections.emptyList();
            }

            String cleaned = rawJson.trim();
            if (cleaned.startsWith("```")) {
                cleaned = cleaned.replaceAll("```json?", "").replaceAll("```", "").trim();
            }

            return objectMapper.readValue(cleaned, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("追问建议生成失败，降级为空数组 | question={}", question, e);
            return Collections.emptyList();
        }
    }
}