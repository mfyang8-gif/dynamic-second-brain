package com.yangmf.mini_nodepad.ai.component;

import com.yangmf.mini_nodepad.ai.aiservice.ContextSummarizer;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executor;

@Slf4j
@Service
public class ChatSummaryService {

    private final StringRedisTemplate stringRedisTemplate;
    private final ContextSummarizer summarizer;
    private final ChatMemoryProvider chatMemoryProvider;
    private final Executor aiTaskExecutor;

    private static final String SUMMARY_KEY_PREFIX = "chat:summary:";
    private static final String COUNTER_KEY_PREFIX = "chat:summary_counter:";
    private static final String NO_SUMMARY = "暂无历史对话摘要";

    @Value("${chat.memory.summary-interval:6}")
    private int summaryInterval;

    @Value("${chat.memory.summary-expire-days:30}")
    private long summaryExpireDays;

    public ChatSummaryService(StringRedisTemplate stringRedisTemplate,
                              ContextSummarizer summarizer,
                              ChatMemoryProvider chatMemoryProvider,
                              @Qualifier("aiTaskExecutor") Executor aiTaskExecutor) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.summarizer = summarizer;
        this.chatMemoryProvider = chatMemoryProvider;
        this.aiTaskExecutor = aiTaskExecutor;
    }

    public String getSummary(String sessionId) {
        String summary = stringRedisTemplate.opsForValue().get(SUMMARY_KEY_PREFIX + sessionId);
        return summary != null ? summary : NO_SUMMARY;
    }

    public void triggerAsyncSummarization(String sessionId) {
        String counterKey = COUNTER_KEY_PREFIX + sessionId;
        Long currentCount = stringRedisTemplate.opsForValue().increment(counterKey, 2);
        stringRedisTemplate.expire(counterKey, Duration.ofDays(summaryExpireDays));

        if (currentCount == null || currentCount < summaryInterval) {
            return;
        }

        stringRedisTemplate.delete(counterKey);

        aiTaskExecutor.execute(() -> {
            try {
                generateSummary(sessionId);
            } catch (Exception e) {
                log.error("异步摘要失败 | sessionId={}", sessionId, e);
            }
        });
    }

    public void clearSummary(String sessionId) {
        stringRedisTemplate.delete(SUMMARY_KEY_PREFIX + sessionId);
        stringRedisTemplate.delete(COUNTER_KEY_PREFIX + sessionId);
    }

    private void generateSummary(String sessionId) {
        ChatMemory memory = chatMemoryProvider.get(sessionId);
        List<ChatMessage> allMessages = memory.messages();
        int size = allMessages.size();

        if (size < summaryInterval) {
            return;
        }

        String oldSummary = stringRedisTemplate.opsForValue().get(SUMMARY_KEY_PREFIX + sessionId);
        if (oldSummary == null) {
            oldSummary = "无";
        }

        int fromIndex = Math.max(0, size - summaryInterval);
        List<ChatMessage> unsummarizedMessages = allMessages.subList(fromIndex, size);

        String newMessagesText = formatNewMessages(unsummarizedMessages);
        if (newMessagesText.isBlank()) {
            return;
        }

        String newSummary = summarizer.summarizeIncremental(oldSummary, newMessagesText);

        if (newSummary != null && !newSummary.isBlank()) {
            stringRedisTemplate.opsForValue().set(
                    SUMMARY_KEY_PREFIX + sessionId,
                    newSummary,
                    Duration.ofDays(summaryExpireDays)
            );
            log.info("增量摘要已更新 | sessionId={}, newMsgCount={}, summaryLength={}",
                    sessionId, unsummarizedMessages.size(), newSummary.length());
        }
    }

    private String formatNewMessages(List<ChatMessage> messages) {
        StringBuilder sb = new StringBuilder();
        for (ChatMessage msg : messages) {
            if (msg.type() == ChatMessageType.USER) {
                UserMessage um = (UserMessage) msg;
                sb.append("用户: ").append(um.singleText()).append("\n");
            } else if (msg.type() == ChatMessageType.AI) {
                AiMessage am = (AiMessage) msg;
                sb.append("助手: ").append(am.text()).append("\n");
            }
        }
        return sb.toString();
    }
}