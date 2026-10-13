package com.yangmf.mini_nodepad.mq;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yangmf.mini_nodepad.ai.aiservice.HabitExtractor;
import com.yangmf.mini_nodepad.mapper.ChatMessageMapper;
import com.yangmf.mini_nodepad.pojo.dto.ExtractedHabit;
import com.yangmf.mini_nodepad.pojo.dto.ExtractedHabitList;
import com.yangmf.mini_nodepad.pojo.entity.ChatMessage;
import com.yangmf.mini_nodepad.service.SemanticMemoryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 语义扫描 Consumer：会话静默 5 分钟后触发一次跨窗口习惯提取。
 * <p>
 * 可靠性设计：
 *   - 消费幂等（盲区 E）：msgId Redis setnx 去重，TTL 10min 覆盖 MQ at-least-once 重复投递窗口，
 *     防止重复累加 hit_count 造成假晋升；
 *   - 只喂 user 消息（盲区 H）：AI 回答中的客串假设句会诱导提取器产生自我强化幻觉；
 *   - catch-all 不抛异常：毒消息不无限 requeue，本轮丢弃依赖下一次会话自然重扫。
 */
@Slf4j
@Component
public class SemanticScanConsumer {

    private static final String CONSUME_KEY_PREFIX = "semantic:consume:";
    private static final Duration CONSUME_DEDUP_TTL = Duration.ofMinutes(10);
    private static final int RECENT_USER_MESSAGES = 10;
    private static final int MIN_MESSAGES_TO_SCAN = 2;
    private static final int PER_MESSAGE_CHAR_BUDGET = 300;

    private final StringRedisTemplate redisTemplate;
    private final ChatMessageMapper chatMessageMapper;
    private final HabitExtractor habitExtractor;
    private final SemanticMemoryService semanticMemoryService;
    private final SemanticScanProducer semanticScanProducer;

    public SemanticScanConsumer(StringRedisTemplate redisTemplate,
                                ChatMessageMapper chatMessageMapper,
                                HabitExtractor habitExtractor,
                                SemanticMemoryService semanticMemoryService,
                                SemanticScanProducer semanticScanProducer) {
        this.redisTemplate = redisTemplate;
        this.chatMessageMapper = chatMessageMapper;
        this.habitExtractor = habitExtractor;
        this.semanticMemoryService = semanticMemoryService;
        this.semanticScanProducer = semanticScanProducer;
    }

    @RabbitListener(queues = RabbitSemanticConfig.QUEUE_SCAN)
    public void onScanMessage(SemanticScanMessage message) {
        if (message == null || message.sessionId() == null || message.userId() == null) {
            log.warn("语义扫描消息缺少必要字段，直接丢弃");
            return;
        }
        try {
            // 消费幂等：重复投递的同一 msgId 只处理一次，防 hit_count 被重复累加
            Boolean firstSeen = redisTemplate.opsForValue().setIfAbsent(
                    CONSUME_KEY_PREFIX + message.msgId(), "1", CONSUME_DEDUP_TTL);
            if (!Boolean.TRUE.equals(firstSeen)) {
                log.info("重复投递的扫描消息，幂等跳过 | sessionId={}, msgId={}",
                        message.sessionId(), message.msgId());
                return;
            }

            List<String> userTurns = fetchRecentUserTurns(message.sessionId());
            if (userTurns.size() < MIN_MESSAGES_TO_SCAN) {
                log.debug("会话用户轮次不足，跳过习惯提取 | sessionId={}, turns={}",
                        message.sessionId(), userTurns.size());
                return;
            }

            StringBuilder dialogue = new StringBuilder();
            for (int i = 0; i < userTurns.size(); i++) {
                dialogue.append(i + 1).append(". ").append(userTurns.get(i)).append('\n');
            }

            // 返回信封 POJO（详见 HabitExtractor 注释）：解包 + null 兜底，绝不让 habits 为 null 外泄
            ExtractedHabitList extracted = habitExtractor.extract(dialogue.toString());
            List<ExtractedHabit> habits = (extracted == null || extracted.habits() == null)
                    ? List.of()
                    : extracted.habits();

            semanticMemoryService.processAndPromote(message.userId(), habits);
            log.info("语义扫描完成 | sessionId={}, userId={}, extracted={}",
                    message.sessionId(), message.userId(), habits.size());
        } catch (Exception e) {
            log.error("语义扫描消费失败，本轮丢弃（下次会话静默期自然重扫） | sessionId={}",
                    message.sessionId(), e);
        } finally {
            // 解除冷却武装：本窗口已消费，用户续聊的下一静默期可重新调度
            semanticScanProducer.releaseArmLock(message.sessionId());
        }
    }

    /**
     * 拉取该会话最近 10 条 user 消息（@TableLogic 自动附加 deleted=0，
     * regenerate 软删的旧轮次天然不进提取窗口）。时间正序 + 单条掐断，控制 Prompt 预算。
     */
    private List<String> fetchRecentUserTurns(String sessionId) {
        List<ChatMessage> messages = chatMessageMapper.selectList(
                new LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getSessionId, sessionId)
                        .eq(ChatMessage::getRole, "user")
                        .orderByDesc(ChatMessage::getCreatedAt)
                        .last("LIMIT " + RECENT_USER_MESSAGES));
        Collections.reverse(messages);
        List<String> turns = new ArrayList<>(messages.size());
        for (ChatMessage m : messages) {
            String content = m.getContent();
            if (content == null || content.isBlank()) {
                continue;
            }
            content = content.trim();
            turns.add(content.length() > PER_MESSAGE_CHAR_BUDGET
                    ? content.substring(0, PER_MESSAGE_CHAR_BUDGET) + "…"
                    : content);
        }
        return turns;
    }
}
