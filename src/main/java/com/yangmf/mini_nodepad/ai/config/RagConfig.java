package com.yangmf.mini_nodepad.ai.config;


import com.yangmf.mini_nodepad.model.DashScopeScoringModel;
import dev.langchain4j.model.scoring.ScoringModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RagConfig {

    @Bean
    public ScoringModel scoringModel(
            @Value("${rag.rerank.api-key}") String apiKey,
            @Value("${rag.rerank.model-name:gte-rerank-v2}") String model,
            @Value("${rag.rerank.base-url:https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank}") String baseUrl,
            @Value("${rag.rerank.timeout-ms:15000}") int timeoutMs) {
        return DashScopeScoringModel.builder()
                .apiKey(apiKey)
                .modelName(model)
                .baseUrl(baseUrl)
                .timeoutMs(timeoutMs)
                .build();
    }
}