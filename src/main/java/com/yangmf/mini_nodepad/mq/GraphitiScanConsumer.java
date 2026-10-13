package com.yangmf.mini_nodepad.mq;

import com.yangmf.mini_nodepad.ai.aiservice.MemorySignalJudge;
import com.yangmf.mini_nodepad.client.GraphitiErrorClassifier;
import com.yangmf.mini_nodepad.enums.GraphitiAuditReason;
import com.yangmf.mini_nodepad.mapper.GraphitiWriteAuditMapper;
import com.yangmf.mini_nodepad.mapper.GraphitiWriteStateMapper;
import com.yangmf.mini_nodepad.pojo.dto.GraphitiPendingMessage;
import com.yangmf.mini_nodepad.pojo.dto.MemorySignalVerdict;
import com.yangmf.mini_nodepad.properties.GraphitiProperties;
import com.yangmf.mini_nodepad.service.GraphitiBackpressureGuard;
import com.yangmf.mini_nodepad.service.HighRiskLexicon;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * Graphiti 扫描消费者（B1 <b>发现层</b>）。
 *
 * <p>流水线（§3.3）：
 * <pre>
 * ① 取窗口   按 sessionId 取 [水位, now] 之间的 user 轮次（水位来自 Redis，回落到消息体）
 * ② 实质判定 ★ 逐轮过 MemorySignalJudge；判否的轮次写【审计表】= 永不投递的终态
 * ③ 逐条投递 每个通过的轮次 → 一条 GraphitiWriteMessage → write.queue（N=1，priority=1）
 * ④ 水位推进 Redis SET graphiti:scan:watermark:{sessionId} = 本轮最大 created_at
 * ⑤ 背压检查 熔断态 → 直接 ack 返回（但【仍然推进水位】）
 * </pre>
 *
 * <p>四个「错一处就静默出事故」的点：
 * <ol>
 *   <li>★ <b>只取 user</b>：AI 回答中的客串假设句会诱导抽取器产生自我强化幻觉。</li>
 *   <li>★ <b>判否必须落审计表</b>：它是「永不（重）投递」的权威标记 ——
 *       少了它，{@code GraphitiReplayJob} 会把判否的闲聊当成「漏网之鱼」投进图谱（前门拦后门灌）。</li>
 *   <li>★ <b>熔断态仍要推进水位</b>：否则下一轮会把同一批消息重新判定一次（白烧 LLM）；
 *       被跳过的消息 {@code dispatched_at} 保持 NULL，由 {@code GraphitiReplayJob} 承接 ——
 *       「延迟 ≠ 丢弃」的分工就在这里。</li>
 *   <li>★ <b>不做 catch-all 吞异常</b>（旧链隐患 2）：本轮异常一律冒泡 → 容器 reject 不 requeue
 *       → 经 DLX 进 {@code graphiti.scan.dlq}（<b>可观测地</b>停泊，而不是静默消失）。
 *       漏扫的轮次由 {@code GraphitiReplayJob} 兜底，水位保证下一轮不重复判定。</li>
 * </ol>
 */
@Slf4j
@Component
public class GraphitiScanConsumer {

    /** 喂给判定器的单轮字符预算（判定只需要语义，不需要全文）。 */
    private static final int JUDGE_CHAR_BUDGET = 1000;

    /** 投递给 Graphiti 的单轮字符预算（与补投层共用，避免两条路径截断长度分叉）。 */
    private static final int CONTENT_CHAR_BUDGET = GraphitiWriteMessage.CONTENT_CHAR_BUDGET;

    /**
     * 无水位时的窗口下界（= 「从最早开始」）。
     *
     * <p>★★ 为什么不是 {@code LocalDateTime.MIN}：MIN 是个 Java 概念，不是 MySQL 概念。
     * 它的年份是 −1000000000，而 MySQL {@code DATETIME} 的定义域只有 1000-01-01 ~ 9999-12-31，
     * 驱动会直接抛 {@code Incorrect DATETIME value: '+1000000000-01-01 00:00:00'}（error 1525）。
     * 后果不是「少扫一次」，而是<b>每个新会话的首次扫描必炸</b>：扫描消息被 reject 不 requeue
     * → 经 DLX 进 DLQ → 发现层对全新会话完全不工作，且只表现为「记忆一直不生成」。
     * （2026-10-13 实机诊断测试复现。这类错误静态审查永远看不出来 —— Java 侧类型完全合法，
     *   只有真正把它绑进 PreparedStatement 打一次真库才会暴露。）
     *
     * <p>取 1970-01-01（Unix 纪元）：既在 DATETIME 定义域内，又远早于本工程任何真实数据，
     * 语义仍是「从最早开始」。
     */
    private static final LocalDateTime EPOCH_FLOOR = LocalDateTime.of(1970, 1, 1, 0, 0, 0);

    private final GraphitiWriteStateMapper stateMapper;
    private final GraphitiWriteAuditMapper auditMapper;
    private final GraphitiScanProducer scanProducer;
    private final MemorySignalJudge memorySignalJudge;
    private final GraphitiBackpressureGuard backpressureGuard;
    private final HighRiskLexicon highRiskLexicon;
    private final GraphitiProperties properties;
    private final RabbitTemplate rabbitTemplate;

    public GraphitiScanConsumer(GraphitiWriteStateMapper stateMapper,
                                GraphitiWriteAuditMapper auditMapper,
                                GraphitiScanProducer scanProducer,
                                MemorySignalJudge memorySignalJudge,
                                GraphitiBackpressureGuard backpressureGuard,
                                HighRiskLexicon highRiskLexicon,
                                GraphitiProperties properties,
                                RabbitTemplate rabbitTemplate) {
        this.stateMapper = stateMapper;
        this.auditMapper = auditMapper;
        this.scanProducer = scanProducer;
        this.memorySignalJudge = memorySignalJudge;
        this.backpressureGuard = backpressureGuard;
        this.highRiskLexicon = highRiskLexicon;
        this.properties = properties;
        this.rabbitTemplate = rabbitTemplate;
    }

    /**
     * ★ {@code autoStartup} 绑定写入链开关：关闭时监听器不启动，
     * 队列里即使有历史消息也不会被消费 —— 保证「开关关着 = 零副作用」。
     * ★ {@code containerFactory} 用专用工厂（prefetch=1 / concurrency=1），
     * 不去改全局 prefetch（那会顺带拖慢旧链路）。
     */
    @RabbitListener(queues = GraphitiMqConfig.QUEUE_SCAN,
            containerFactory = GraphitiMqConfig.LISTENER_FACTORY,
            autoStartup = "${graphiti.write.enabled:false}")
    public void onScanMessage(GraphitiScanMessage message) {
        if (message == null || message.sessionId() == null || message.userId() == null) {
            log.warn("Graphiti 扫描消息缺少必要字段，直接丢弃（无法定位会话，重试无意义）");
            return;
        }
        String sessionId = message.sessionId();

        LocalDateTime since = resolveWindowStart(sessionId, message.watermark());
        int limit = Math.max(1, properties.getWrite().getScanWindowLimit());
        List<GraphitiPendingMessage> turns = stateMapper.selectScanWindow(sessionId, since, limit);

        if (turns.isEmpty()) {
            log.debug("Graphiti 扫描窗口为空 | sessionId={}, since={}", sessionId, since);
            scanProducer.releaseArmLock(sessionId);
            return;
        }

        String userId = turns.get(0).getUserId();
        if (userId == null || userId.isBlank()) {
            // 会话归属链断裂（book.user_id 为空）→ 冒泡：让消息进 DLQ 可观测，不要静默丢
            throw new IllegalStateException("扫描窗口内消息的 userId 为空（chat_session → book 归属链断裂） | sessionId=" + sessionId);
        }

        LocalDateTime maxCreatedAt = turns.get(turns.size() - 1).getCreatedAt();
        boolean frozen = backpressureGuard.isFrozen();

        int passed = 0;
        int rejected = 0;
        int skippedByBackpressure = 0;

        for (GraphitiPendingMessage turn : turns) {
            if (auditMapper.existsByMessageId(turn.getMessageId()) > 0) {
                // 已进入终态（判否 / 毒消息 / 耗尽）—— 永不被（重）投
                continue;
            }
            if (!shouldRemember(turn)) {
                auditMapper.insertIgnore(userId, turn.getMessageId(), GraphitiAuditReason.JUDGE_REJECTED.name(),
                        snapshot(turn.getContent()), "MemorySignalJudge 判否：不含用户属性/事实");
                rejected++;
                continue;
            }
            if (frozen) {
                // ★ 熔断态：不投递，但也不写审计表（保持 dispatched_at = NULL，交给 ReplayJob 补投）
                skippedByBackpressure++;
                continue;
            }
            publishNormal(turn, userId);
            passed++;
        }

        // ★ 无论是否熔断都推进水位：避免下一轮重复判定同一批
        scanProducer.advanceWatermark(sessionId, maxCreatedAt);
        scanProducer.releaseArmLock(sessionId);

        log.info("Graphiti 扫描完成 | sessionId={}, userId={}, 窗口={}, 投递={}, 判否={}, 熔断跳过={}, frozen={}",
                sessionId, userId, turns.size(), passed, rejected, skippedByBackpressure, frozen);
    }

    /**
     * 窗口起点：Redis 水位与消息体水位取「更晚」的那个。
     * <p>两者都为空的场合（首次扫描）返回 {@link #EPOCH_FLOOR} ——
     * 语义等价于「从最早开始」，且只多扫一次（第一次扫描后水位即建立）。
     * ★ 刻意不用 {@code null}：参数化 SQL 里绑 null 需要显式 jdbcType，
     * 写 {@code <if>} 分支会让这条关键 SQL 变脆；用一个足够早的常量更稳且行为可预期。
     */
    private LocalDateTime resolveWindowStart(String sessionId, String messageWatermark) {
        LocalDateTime fromRedis = parseIso(scanProducer.readWatermark(sessionId));
        LocalDateTime fromMessage = parseIso(messageWatermark);
        if (fromRedis == null && fromMessage == null) {
            return EPOCH_FLOOR;
        }
        if (fromRedis == null) {
            return fromMessage;
        }
        if (fromMessage == null) {
            return fromRedis;
        }
        return fromRedis.isAfter(fromMessage) ? fromRedis : fromMessage;
    }

    private LocalDateTime parseIso(String iso) {
        if (iso == null || iso.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(iso);
        } catch (DateTimeParseException e) {
            log.warn("水位格式非法，按无水位处理 | watermark={}", iso);
            return null;
        }
    }

    /**
     * 实质判定。
     *
     * <p>★ 三条裁决：
     * <ul>
     *   <li>判定器<b>关闭</b>时放行全部 —— 「全量投递」比「误拦真记忆」安全
     *       （前者代价是成本与噪音，后者是记忆缺失）；</li>
     *   <li>判定器<b>调用失败</b>时放行并打 WARN —— 一次模型抖动不该让用户的真实偏好永久消失；
     *       噪音由下游的图谱质量与召回预算收敛。</li>
     *   <li>高危词表命中时<b>直接放行</b>（不调 LLM）：这类事实的时效价值最高，
     *       省一次 LLM 往返也省一次失败机会。</li>
     * </ul>
     */
    private boolean shouldRemember(GraphitiPendingMessage turn) {
        String content = turn.getContent();
        if (content == null || content.isBlank()) {
            return false;
        }
        if (highRiskLexicon.hit(content)) {
            return true;
        }
        if (!properties.getWrite().isJudgeEnabled()) {
            return true;
        }
        try {
            MemorySignalVerdict verdict = memorySignalJudge.judge(budget(content, JUDGE_CHAR_BUDGET));
            if (verdict == null) {
                log.warn("实质判定返回 null，保守放行 | messageId={}", turn.getMessageId());
                return true;
            }
            if (!verdict.shouldRemember()) {
                log.info("实质判定判否 | messageId={}, reason={}", turn.getMessageId(), verdict.reason());
            }
            return verdict.shouldRemember();
        } catch (Exception e) {
            log.warn("实质判定调用失败，保守放行（宁可多投也不丢记忆） | messageId={}", turn.getMessageId(), e);
            return true;
        }
    }

    /**
     * 投一条常规消息到唯一写入口。
     *
     * <p>★ 显式设置 {@code priority}：优先级是<b>消息属性</b>，不设就落到队列默认值 0，
     * 而高危消息是 10 —— 不显式设等于「全员平民」，优先级队列形同虚设。
     */
    private void publishNormal(GraphitiPendingMessage turn, String userId) {
        String createdAtIso = turn.getCreatedAt() == null ? null : turn.getCreatedAt().toString();
        GraphitiWriteMessage out = GraphitiWriteMessage.normal(
                turn.getMessageId(),
                turn.getSessionId(),
                userId,
                budget(turn.getContent(), CONTENT_CHAR_BUDGET),
                createdAtIso,
                "会话 " + turn.getSessionId() + " 的 5 分钟静默批量扫描");
        rabbitTemplate.convertAndSend(GraphitiMqConfig.EXCHANGE_WRITE, GraphitiMqConfig.ROUTING_WRITE, out,
                msg -> {
                    msg.getMessageProperties().setPriority(out.priority());
                    return msg;
                });
    }

    /** 单条消息的字符预算（P7 层 4 抽样需要原文，故上限放宽到 512）。 */
    private String snapshot(String content) {
        return budget(content, 512);
    }

    private String budget(String text, int max) {
        if (text == null) {
            return null;
        }
        String trimmed = text.strip();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max) + "…";
    }

    /** 供日志统计使用：是否已达重试上限（与 {@link GraphitiErrorClassifier} 同源）。 */
    static boolean exhausted(int attempt) {
        return GraphitiErrorClassifier.isExhausted(attempt);
    }
}
