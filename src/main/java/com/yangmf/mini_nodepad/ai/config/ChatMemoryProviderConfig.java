package com.yangmf.mini_nodepad.ai.config;

import dev.langchain4j.community.store.memory.chat.redis.RedisChatMemoryStore;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ChatMemoryProviderConfig {

    @Bean
    public ChatMemoryProvider chatMemoryProvider(
            @Value("${spring.data.redis.host:localhost}") String redisHost,
            @Value("${spring.data.redis.port:6380}") int redisPort,
            @Value("${chat.memory.max-messages:20}") int maxMessages) {

        RedisChatMemoryStore store = RedisChatMemoryStore.builder()
                .host(redisHost)
                .port(redisPort)
                .build();

        return memoryId -> MessageWindowChatMemory.builder()
                .id(memoryId)
                .maxMessages(maxMessages)
                .chatMemoryStore(store)
                .build();
    }
}