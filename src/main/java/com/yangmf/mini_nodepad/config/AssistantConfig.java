package com.yangmf.mini_nodepad.config;


import com.yangmf.mini_nodepad.aiservice.GeneralAssistant;
import com.yangmf.mini_nodepad.aiservice.PageAssistant;
import dev.langchain4j.community.model.dashscope.QwenChatModel;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AssistantConfig {




    private final OpenAiChatModel doubaoModel;


    private final QwenChatModel qwenChatModel;


    private final OpenAiStreamingChatModel streamingModel;

    private final ChatMemoryProvider chatMemoryProvider;

    public AssistantConfig(OpenAiChatModel doubaoModel, QwenChatModel qwenChatModel, OpenAiStreamingChatModel streamingModel, ChatMemoryProvider chatMemoryProvider) {
        this.doubaoModel = doubaoModel;
        this.qwenChatModel = qwenChatModel;
        this.streamingModel = streamingModel;
        this.chatMemoryProvider = chatMemoryProvider;
    }

    @Bean
    public PageAssistant pageAssistant() {
        return AiServices.builder(PageAssistant.class)
                .chatModel(qwenChatModel)
                .build();
    }

    @Bean
    public GeneralAssistant generalAssistant() {
        return AiServices.builder(GeneralAssistant.class)
                .chatModel(qwenChatModel)
                .build();
    }


}
