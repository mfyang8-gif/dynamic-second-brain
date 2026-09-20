package com.yangmf.mini_nodepad.config;

import com.yangmf.mini_nodepad.aiservice.ContextSummarizer;
import dev.langchain4j.community.store.memory.chat.redis.RedisChatMemoryStore;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
public class ChatMemoryProviderConfig {

    /**
     * 直接在方法参数上声明需要的依赖和 @Value 配置。
     * Spring 看到这个 @Bean 方法时，会自动把这些参数准备好并传进来。
     */
    @Bean
    public ChatMemoryProvider chatMemoryProvider(
            ContextSummarizer summarizer,
            OpenAiChatModel chatModel,
            StringRedisTemplate stringRedisTemplate,
            @Value("${spring.data.redis.host:localhost}") String redisHost,
            @Value("${spring.data.redis.port:6380}") int redisPort,
            @Value("${chat.memory.token-threshold:1000}") int tokenThreshold,
            @Value("${chat.memory.summary-check-interval:3}") int summaryCheckInterval,
            @Value("${chat.memory.summary-expire-days:30}") long summaryExpireDays) {

        ChatMemoryProvider baseProvider = memoryId -> MessageWindowChatMemory.builder()
                .chatMemoryStore(RedisChatMemoryStore.builder()
                        .host(redisHost)
                        .port(redisPort)
                        .build())
                .id(memoryId)
                .maxMessages(20)
                .build();

        return new SummarizingChatMemoryProvider(baseProvider, summarizer, chatModel, stringRedisTemplate,
                tokenThreshold, summaryCheckInterval, summaryExpireDays);
    }
}