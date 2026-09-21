package com.yangmf.mini_nodepad.ai.config;


import com.yangmf.mini_nodepad.ai.aiservice.*;

import dev.langchain4j.community.model.dashscope.QwenChatModel;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.tool.ToolProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


@Configuration
public class AssistantConfig {


    private final ToolProvider allToolProvider;



    private final OpenAiChatModel doubaoModel;


    private final QwenChatModel qwenChatModel;


    private final OpenAiStreamingChatModel streamingModel;

    private final ChatMemoryProvider chatMemoryProvider;



    public AssistantConfig(OpenAiChatModel doubaoModel, QwenChatModel qwenChatModel, OpenAiStreamingChatModel streamingModel, @Qualifier("chatMemoryProvider")ChatMemoryProvider chatMemoryProvider,   @Qualifier("mcpToolProvider")ToolProvider allToolProvider) {
        this.doubaoModel = doubaoModel;
        this.qwenChatModel = qwenChatModel;
        this.streamingModel = streamingModel;
        this.chatMemoryProvider = chatMemoryProvider;
        this.allToolProvider = allToolProvider;
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

    @Bean
    public MainChatAssistant mainChatAssistant() {
        return AiServices.builder(MainChatAssistant.class)
                .streamingChatModel(streamingModel)
                .toolProvider(allToolProvider)
                .chatMemoryProvider(chatMemoryProvider)
                .build();
    }

    @Bean
    public QueryRewriteAssistant queryAssistant() {
        return AiServices.builder(QueryRewriteAssistant.class)
                .chatModel(qwenChatModel)
                .build();
    }


    @Bean
    public IntentRouter intentRouter() {
        return AiServices.builder(IntentRouter.class)
                .chatModel(qwenChatModel)
                .build();
    }




}
