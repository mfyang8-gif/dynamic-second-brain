package com.yangmf.mini_nodepad.config;


import com.yangmf.mini_nodepad.aiservice.ContextSummarizer;
import dev.langchain4j.data.message.*;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.model.chat.ChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;


@Slf4j
public class SummarizingChatMemoryProvider implements ChatMemoryProvider {

    private final ChatMemoryProvider delegateProvider;
    private final ContextSummarizer summarizer;
    private final ChatModel chatModel;


    private  final StringRedisTemplate stringRedisTemplate;

    private static final int TOKEN_THRESHOLD = 1000;
    private static final int SUMMARY_CHECK_INTERVAL = 3;
    private static final String SUMMARY_KEY_PREFIX = "chat:summary:";
    private static final String MESSAGE_COUNT_KEY_PREFIX = "chat:msgcount:";
    private static final long SUMMARY_EXPIRE_DAYS = 30;

    public SummarizingChatMemoryProvider(
            ChatMemoryProvider delegateProvider,
            ContextSummarizer summarizer,
            ChatModel chatModel,
            StringRedisTemplate stringRedisTemplate) {
        this.delegateProvider = delegateProvider;
        this.stringRedisTemplate = stringRedisTemplate;
        this.summarizer = summarizer;
        this.chatModel = chatModel;
    }

    @Override
    public ChatMemory get(Object memoryId) {
        return new SummarizingChatMemory(memoryId, delegateProvider.get(memoryId));
    }

    private class SummarizingChatMemory implements ChatMemory {

        private final Object memoryId;
        private final ChatMemory delegate;

        public SummarizingChatMemory(Object memoryId, ChatMemory delegate) {
            this.memoryId = memoryId;
            this.delegate = delegate;
        }

        @Override
        public void add(ChatMessage message) {
            delegate.add(message);
            incrementMessageCount();

            int count = getMessageCount();
            if (count % SUMMARY_CHECK_INTERVAL == 0) {
                checkAndSummarize();
            }
        }

        @Override
        public List<ChatMessage> messages() {
            List<ChatMessage> messages = delegate.messages();
            String summary = getSummary();

            if (summary != null && !summary.isEmpty()) {
                List<ChatMessage> result = new ArrayList<>();
                result.add(SystemMessage.from("以下是之前对话的总结：" + summary));

                int recentMessagesCount = Math.min(20, messages.size());
                if (messages.size() > recentMessagesCount) {
                    result.addAll(messages.subList(messages.size() - recentMessagesCount, messages.size()));
                } else {
                    result.addAll(messages);
                }
                return result;
            }
            return messages;
        }

        @Override
        public void clear() {
            delegate.clear();
            clearSummary();
            clearMessageCount();
        }

        @Override
        public String id() {
            return (String) delegate.id();
        }

        private void checkAndSummarize() {
            try {
                List<ChatMessage> messages = delegate.messages();
                if (messages.size() < 6) {return;
                }

                int estimatedTokens = estimateTokens(messages);
                if (estimatedTokens > TOKEN_THRESHOLD) {
                    log.info("Token 数量 {} 超过阈值 {}，开始总结，memoryId={}", estimatedTokens, TOKEN_THRESHOLD, memoryId);
                    String conversationText = convertMessagesToText(messages);
                    String summary = summarizer.summarize(conversationText);

                    if (summary != null && !summary.isEmpty()) {
                        saveSummary(summary);

                        int messagesToKeep = Math.min(20, messages.size());
                        List<ChatMessage> recentMessages = messages.subList(messages.size() - messagesToKeep, messages.size());

                        delegate.clear();
                        for (ChatMessage msg : recentMessages) {
                            delegate.add(msg);
                        }

                        clearMessageCount();
                        for (int i = 0; i < messagesToKeep; i++) {
                            incrementMessageCount();
                        }
                        log.info("总结完成，memoryId={}, 总结长度={}", memoryId, summary.length());
                    }
                }
            } catch (Exception e) {
                log.error("总结失败，memoryId={}", memoryId, e);
            }
        }

        private void incrementMessageCount() {
            String key = MESSAGE_COUNT_KEY_PREFIX + memoryId.toString();
            stringRedisTemplate.opsForValue().increment(key);
            stringRedisTemplate.expire(key, Duration.ofDays(SUMMARY_EXPIRE_DAYS));
        }

        private int getMessageCount() {
            String key = MESSAGE_COUNT_KEY_PREFIX + memoryId.toString();
            String count = stringRedisTemplate.opsForValue().get(key);
            return count != null ? Integer.parseInt(count) : 0;
        }

        private void clearMessageCount() {
            stringRedisTemplate.delete(MESSAGE_COUNT_KEY_PREFIX + memoryId.toString());
        }

        private void saveSummary(String summary) {
            String key = SUMMARY_KEY_PREFIX + memoryId.toString();
            stringRedisTemplate.opsForValue().set(key, summary, Duration.ofDays(SUMMARY_EXPIRE_DAYS));
        }

        private String getSummary() {
            return stringRedisTemplate.opsForValue().get(SUMMARY_KEY_PREFIX + memoryId.toString());
        }

        private void clearSummary() {
            stringRedisTemplate.delete(SUMMARY_KEY_PREFIX + memoryId.toString());
        }

        private int estimateTokens(List<ChatMessage> messages) {
            return messages.stream().mapToInt(this::extractTextLength).sum();
        }

        private String convertMessagesToText(List<ChatMessage> messages) {
            StringBuilder sb = new StringBuilder();
            for (ChatMessage msg : messages) {
                String text = extractText(msg);
                if (text != null && !text.isEmpty()) {
                    sb.append(msg.type().toString()).append(": ").append(text).append("\n");
                }
            }
            return sb.toString();
        }

        private String extractText(ChatMessage message) {
            if (message instanceof SystemMessage) {
                return ((SystemMessage) message).text();
            } else if (message instanceof AiMessage) {
                return ((AiMessage) message).text();
            } else if (message instanceof UserMessage) {
                UserMessage userMessage = (UserMessage) message;
                List<Content> contents = userMessage.contents();
                if (contents != null && !contents.isEmpty()) {
                    StringBuilder textBuilder = new StringBuilder();
                    for (Content content : contents) {
                        if (content instanceof TextContent) {
                            textBuilder.append(((TextContent) content).text());
                        }
                    }
                    return textBuilder.toString();
                }
            }
            return "";
        }

        private int extractTextLength(ChatMessage message) {
            String text = extractText(message);
            return text != null ? text.length() / 3 : 0;
        }
    }
}
