package com.yangmf.mini_nodepad.config;


import com.yangmf.mini_nodepad.aiservice.ContextSummarizer;
import dev.langchain4j.community.model.dashscope.QwenChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SummarizerConfig {



    private QwenChatModel qwenChatModel;

    public SummarizerConfig(QwenChatModel qwenChatModel) {
        this.qwenChatModel = qwenChatModel;
    }

    @Bean
    public ContextSummarizer contextSummarizer() {
        return AiServices.builder(ContextSummarizer.class)
                .chatModel(qwenChatModel)
                .build();
    }
}
