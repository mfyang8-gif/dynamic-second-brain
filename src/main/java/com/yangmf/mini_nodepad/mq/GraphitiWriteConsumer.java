package com.yangmf.mini_nodepad.mq;

import com.yangmf.mini_nodepad.client.GraphitiClientException;
import com.yangmf.mini_nodepad.client.GraphitiErrorClassifier;
import com.yangmf.mini_nodepad.client.GraphitiMemoryClient;
import com.yangmf.mini_nodepad.client.GroupIdResolver;
import com.yangmf.mini_nodepad.client.dto.GraphitiMessage;
import com.yangmf.mini_nodepad.client.dto.GraphitiRoleType;
import com.yangmf.mini_nodepad.client.dto.GraphitiWriteAck;
import com.yangmf.mini_nodepad.enums.GraphitiAuditReason;
import com.yangmf.mini_nodepad.mapper.GraphitiWriteAuditMapper;
import com.yangmf.mini_nodepad.mapper.GraphitiWriteStateMapper;
import com.yangmf.mini_nodepad.service.HighRiskLexicon;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * Graphiti 投递消费者（B1 <b>投递层</b>，N=1）。
 *
 * <p>流水线（§3.3）：
 * <pre>
 * ① CAS 占位   UPDATE ... SET dispatched_at = NOW()
 *                WHERE id = ? AND (dispatched_at IS NULL OR dispatched_at &lt; NOW() - 3 MINUTE)
 *              → 影响 0 行 = 已被投过 → 直接 ack 跳过（不去 POST）
 * ② 投递       POST /messages（单条）
 * ③ 收 202     → 立即 ack（不等落库；确认交给 GraphitiConfirmJob 异步做）
 * ④ 异常分类   见 {@link GraphitiErrorClassifier}
 * ⑤ 重投保全   进 retry 队列时必须【显式 setPriority(原值)】
 * ⑥ 前置复核   毒消息判定前先查审计表：已隔离的直接 ack，不 POST、不 DLQ
 * </pre>
 *
 * <p>★★ 三条决定「会不会丢数据」的约束：
 * <ol>
 *   <li><b>POST 之前先 CAS 占位</b>（不是之后）。若先 POST 再写 {@code dispatched_at}，
 *       一次 ack 丢失导致的 MQ 重投就会在「写字段之前」再次 POST → 重复 episode。
 *       Graphiti 服务端对重复投递<b>零去重能力</b>，CAS 是唯一防线。</li>
 *   <li><b>可重试失败必须回滚占位</b>。不回滚的话，重试消息回来后 CAS 发现
 *       {@code dispatched_at} 非空 → 直接 ack 跳过 → <b>整条 MQ 重试链路当场瘫痪</b>。</li>
 *   <li><b>毒消息保留占位 + 写审计表</b>。只保留占位不写审计表，对账任务扫到它
 *       （{@code IS NOT NULL} 且查不到锚点）→ 判定投递失败 → 重投 → 又 400 →
 *       <b>DLQ ↔ Confirm 无限互踢</b>。审计表让「毒消息」变成终态，而非「待确认」。</li>
 * </ol>
 *
 * <p>⚠️ 本监听器<b>永不向外抛业务异常</b>：{@code default-requeue-rejected=false} 下
 * 抛出即等于「reject 且不 requeue」。所有可预期的失败都在方法内被分类、落库、路由；
 * 只有「连重试队列都投不进去」这种基础设施故障才会主动
 * {@code AmqpRejectAndDontRequeueException} → 经 DLX 停泊到 {@code graphiti.write.dlq}，
 * 而此时 {@code dispatched_at} 已被回滚为 NULL → 由 {@code GraphitiReplayJob} 兜底补投。
 */
@Slf4j
@Component
public class GraphitiWriteConsumer {

    private final GraphitiMemoryClient client;
    private final GraphitiWriteStateMapper stateMapper;
    private final GraphitiWriteAuditMapper auditMapper;
    private final GroupIdResolver groupIdResolver;
    private final HighRiskLexicon highRiskLexicon;
    private final RabbitTemplate rabbitTemplate;

    public GraphitiWriteConsumer(GraphitiMemoryClient client,
                                 GraphitiWriteStateMapper stateMapper,
                                 GraphitiWriteAuditMapper auditMapper,
                                 GroupIdResolver groupIdResolver,
                                 HighRiskLexicon highRiskLexicon,
                                 RabbitTemplate rabbitTemplate) {
        this.client = client;
        this.stateMapper = stateMapper;
        this.auditMapper = auditMapper;
        this.groupIdResolver = groupIdResolver;
        this.highRiskLexicon = highRiskLexicon;
        this.rabbitTemplate = rabbitTemplate;
    }

    /**
     * ★ {@code prefetch=1 / concurrency=1} 不是保守而是必须：Graphiti 侧是<b>单协程串行</b>
     * 消费 {@code asyncio.Queue}，压更多并发只是把排队搬到服务端，而我们这边反而多占线程。
     */
    @RabbitListener(queues = GraphitiMqConfig.QUEUE_WRITE,
            containerFactory = GraphitiMqConfig.LISTENER_FACTORY,
            autoStartup = "${graphiti.write.enabled:false}")
    public void onWriteMessage(GraphitiWriteMessage message) {
        if (message == null || message.messageId() == null) {
            log.warn("Graphiti 投递消息缺少 messageId，无法定位确认锚点，直接丢弃");
            return;
        }

        // ⑥ 前置复核：已进入终态（判否 / 毒 / 耗尽）的消息永不再 POST，也不进 DLQ
        if (auditMapper.existsByMessageId(message.messageId()) > 0) {
            log.info("消息已在审计终态，投递层直接 ack 丢弃 | messageId={}", message.messageId());
            return;
        }

        Long userId = parseUserId(message.userId());
        OffsetDateTime timestamp = parseTimestamp(message.createdAtIso());
        if (userId == null || timestamp == null) {
            // 归属或时间无法还原 = 数据完整性问题，重试不可能修好 → 终态隔离
            auditMapper.insertIgnore(message.userId(), message.messageId(),
                    GraphitiAuditReason.POISON.name(), snapshot(message.content()),
                    userId == null ? "userId 非法：" + message.userId() : "createdAt 非法：" + message.createdAtIso());
            log.error("消息载荷不完整，隔离为毒消息 | messageId={}, userId={}, createdAtIso={}",
                    message.messageId(), message.userId(), message.createdAtIso());
            return;
        }

        // ① CAS 占位（必须在 POST 之前）
        int occupied = stateMapper.casOccupy(message.messageId());
        if (occupied == 0) {
            log.info("CAS 占位失败：该消息已被投递过，跳过 | messageId={}", message.messageId());
            return;
        }

        try {
            GraphitiMessage payload = buildPayload(message, timestamp);
            GraphitiWriteAck ack = client.write(userId, payload);
            // ③ 收到 202 即 ack：确认交给对账任务，消费者永不阻塞（v2 的「消费者内轮询」会占住线程最多 15 分钟）
            log.info("Graphiti 投递受理 | messageId={}, accepted={}, attempt={}, priority={}",
                    message.messageId(), ack != null && ack.accepted(), message.attempt(), message.priority());
        } catch (GraphitiClientException e) {
            handleFailure(message, userId, e);
        } catch (Exception e) {
            // 任何未预期异常一律「保守可重试」：宁可白重试三次后进 DLQ，也不要当场判死
            handleFailure(message, userId,
                    GraphitiClientException.of("write", null, e.getMessage(), e));
        }
    }

    /**
     * 失败路由（补丁 1 + 补丁 4 的完整分类）。
     *
     * <table border="1">
     *   <caption>分类与动作</caption>
     *   <tr><th>分类</th><th>占位</th><th>动作</th></tr>
     *   <tr><td>TOXIC（400/422）</td><td><b>保留</b></td><td>进 DLQ + 写审计表 POISON（终态）</td></tr>
     *   <tr><td>RATE_LIMITED（429/403/quota）</td><td>回滚</td><td>跳过 30s 档，直接 2min / 10min</td></tr>
     *   <tr><td>RETRYABLE（连接拒绝/超时/5xx）</td><td>回滚</td><td>按 attempt 进 30s / 2min / 10min</td></tr>
     *   <tr><td>任一分类且 attempt 耗尽</td><td>回滚</td><td>进 DLQ + 写审计表 RETRY_EXHAUSTED</td></tr>
     * </table>
     */
    private void handleFailure(GraphitiWriteMessage message, Long userId, GraphitiClientException error) {
        GraphitiErrorClassifier.Kind kind = GraphitiErrorClassifier.classify(error);

        if (kind == GraphitiErrorClassifier.Kind.TOXIC) {
            // 保留占位（避免坏消息无限重投）+ 写审计表（避免与对账任务无限互踢）
            auditMapper.insertIgnore(message.userId(), message.messageId(),
                    GraphitiAuditReason.POISON.name(), snapshot(message.content()),
                    "毒消息：" + truncate(error.getMessage(), 300));
            sendToDlq(message, "TOXIC");
            log.error("毒消息隔离并转入 DLQ | messageId={}, kind={}, err={}",
                    message.messageId(), kind, truncate(error.getMessage(), 300));
            return;
        }

        // 可重试 / 限流：先回滚占位，否则消息会被自己的防重机制永久拒收
        stateMapper.rollbackOccupy(message.messageId());

        if (GraphitiErrorClassifier.isExhausted(message.attempt())) {
            auditMapper.insertIgnore(message.userId(), message.messageId(),
                    GraphitiAuditReason.RETRY_EXHAUSTED.name(), snapshot(message.content()),
                    "重试耗尽(" + message.attempt() + ")：" + truncate(error.getMessage(), 240));
            sendToDlq(message, "RETRY_EXHAUSTED");
            log.error("重试耗尽，隔离并转入 DLQ | messageId={}, attempt={}, kind={}",
                    message.messageId(), message.attempt(), kind);
            return;
        }

        int level = GraphitiErrorClassifier.backoffLevel(message.attempt(), kind);
        String queue = GraphitiMqConfig.retryQueueOf(level);
        GraphitiWriteMessage next = message.nextAttempt();
        try {
            rabbitTemplate.convertAndSend("", queue, next, msg -> {
                // ★ 显式保优先级：priority 是消息属性，自己构造 Message 就必须自己带
                msg.getMessageProperties().setPriority(next.priority());
                return msg;
            });
            log.warn("投递失败已进入重试队列 | messageId={}, kind={}, attempt={} → {}, priority={}",
                    message.messageId(), kind, next.attempt(), queue, next.priority());
        } catch (Exception publishError) {
            // 连重试队列都投不进去 = 基础设施故障：主动 reject 不 requeue → 经 DLX 停泊到 DLQ。
            // 此时 dispatched_at 已回滚为 NULL，ReplayJob 会在基础设施恢复后补投（延迟 ≠ 丢弃）。
            log.error("重试队列投递失败，转由 DLQ 停泊（dispatched_at 已回滚，ReplayJob 将兜底） | messageId={}",
                    message.messageId(), publishError);
            throw new AmqpRejectAndDontRequeueException("重试队列不可用，停泊到 DLQ", publishError);
        }
    }

    private GraphitiMessage buildPayload(GraphitiWriteMessage message, OffsetDateTime timestamp) {
        return GraphitiMessage.forChatMessage(
                message.messageId(),
                message.content(),
                GraphitiRoleType.USER,
                timestamp,
                message.sourceDescription());
    }

    private void sendToDlq(GraphitiWriteMessage message, String reason) {
        try {
            rabbitTemplate.convertAndSend("", GraphitiMqConfig.QUEUE_WRITE_DLQ, message, msg -> {
                msg.getMessageProperties().setPriority(message.priority());
                msg.getMessageProperties().setHeader("x-graphiti-dlq-reason", reason);
                return msg;
            });
        } catch (Exception e) {
            // DLQ 也投不进去：至少审计表已有终态记录，数据可人工恢复；此处只记日志不打爆监听器
            log.error("DLQ 投递失败（审计表已有终态记录，可人工恢复） | messageId={}", message.messageId(), e);
        }
    }

    /**
     * 把库中的字符串用户主键（{@code book.user_id} VARCHAR）严格解析为数值型。
     *
     * @return 非法时返回 null（调用方按数据完整性问题隔离为终态，而不是无限重试）
     */
    private Long parseUserId(String raw) {
        return GroupIdResolver.parseUserId(raw);
    }

    /**
     * 把 ISO-8601 本地时间还原成带 offset 的时刻。
     *
     * <p>★ 必须显式带 offset：Graphiti 的 {@code reference_time} 是双时间戳裁决的<b>唯一依据</b>，
     * 不传就会被服务端默认成 {@code utc_now()}（= 处理时刻），历史对话的时序全部失真。
     * 使用应用所在时区（与 JDBC {@code serverTimezone=Asia/Shanghai} 一致）。
     */
    private OffsetDateTime parseTimestamp(String iso) {
        if (iso == null || iso.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(iso).atZone(ZoneId.systemDefault()).toOffsetDateTime();
        } catch (Exception e) {
            return null;
        }
    }

    private String snapshot(String content) {
        if (content == null) {
            return null;
        }
        String trimmed = content.strip();
        return trimmed.length() <= 512 ? trimmed : trimmed.substring(0, 512) + "…";
    }

    private String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }

    /** 保留字段：便于后续按来源区分日志（当前仅用于诊断）。 */
    List<String> describeRisk(String content) {
        return highRiskLexicon.matched(content);
    }
}
