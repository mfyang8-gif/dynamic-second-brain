package com.yangmf.mini_nodepad.mq;

import com.yangmf.mini_nodepad.properties.GraphitiProperties;
import com.yangmf.mini_nodepad.service.HighRiskLexicon;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Graphiti 扫描调度 Producer（B1 发现层的入口）。
 *
 * <p>职责与旧链的 {@code SemanticScanProducer} 一一对应，只有一处语义变化：
 * <b>投递目标换成 {@code graphiti.scan.delay.queue}</b>，并且消息体带上「武装时刻的水位」。
 *
 * <p>三个保留 / 修正点：
 * <ol>
 *   <li><b>保留冷却锁</b>：Redis setnx {@code graphiti:scan:armed:{sessionId}} TTL 5min，
 *       把「N 轮对话 N 次 LLM 分析」收敛为「会话静默 5 分钟后仅扫一次」。</li>
 *   <li>★ <b>删除消费侧的去重锁</b>：旧链有 {@code setnx(scan:dedup:{sessionId}:{watermark}, TTL 1h)}，
 *       它是个双料 bug —— ① 死锁：若 scan 中途抛异常，锁已占、水位未推进，
 *       下一轮用<b>同一个 key</b> 再 setnx 就被自己的锁挡住，该轮<b>永久漏扫</b>；
 *       ② 零收益：<b>水位本身就是幂等</b>（推进后不回头），
 *       去重锁提供的「额外幂等」不存在，只贡献了一个死锁风险。
 *       → B1 起由「水位」独任幂等。</li>
 *   <li>★ <b>水位是结构性根治</b>：有了水位，即使某一轮漏扫，下一轮也能从水位继续 ——
 *       而不是像旧链那样只靠「用户恰好又说话了」。</li>
 * </ol>
 *
 * <p>⚠️ 武装锁的释放必须<b>有条件</b>（旧链在 {@code finally} 里无条件释放是隐患 3）：
 * 本轮消费失败时若把锁释放掉，丢失的那一轮不会被补扫。
 * → 本类只提供 {@link #releaseArmLock}，由消费侧在<b>成功路径</b>调用。
 */
@Slf4j
@Component
public class GraphitiScanProducer {

    private static final String ARMED_KEY_PREFIX = "graphiti:scan:armed:";
    private static final String WATERMARK_KEY_PREFIX = "graphiti:scan:watermark:";

    private final StringRedisTemplate redisTemplate;
    private final RabbitTemplate rabbitTemplate;
    private final GraphitiProperties properties;
    private final HighRiskLexicon highRiskLexicon;

    public GraphitiScanProducer(StringRedisTemplate redisTemplate,
                                RabbitTemplate rabbitTemplate,
                                GraphitiProperties properties,
                                HighRiskLexicon highRiskLexicon) {
        this.redisTemplate = redisTemplate;
        this.rabbitTemplate = rabbitTemplate;
        this.properties = properties;
        this.highRiskLexicon = highRiskLexicon;
    }

    /**
     * 为会话「武装」一次延时扫描（落库成功后调用）。
     *
     * <p>⚠️ 本方法<b>只在写入链开启时投递</b>：关闭时调用方应回落到旧链，
     * 因此这里再做一次开关判断作为双保险（避免误触发产生无人消费的消息）。
     *
     * <p>发送失败仅降级记日志，绝不影响 {@code persistMessages} 主链路。
     */
    public void armSessionScan(String sessionId, Long userId) {
        if (!properties.getWrite().isEnabled()) {
            log.debug("Graphiti 写入链未开启，跳过扫描武装 | sessionId={}", sessionId);
            return;
        }
        try {
            Boolean armed = redisTemplate.opsForValue().setIfAbsent(
                    ARMED_KEY_PREFIX + sessionId, "1", Duration.ofMillis(GraphitiMqConfig.SCAN_DELAY_MS));
            if (!Boolean.TRUE.equals(armed)) {
                log.debug("Graphiti 扫描已武装，跳过重复调度 | sessionId={}", sessionId);
                return;
            }
            // 武装时刻把「当前水位」随消息带走：消费侧优先用 Redis 里的最新水位，
            // Redis 无值（首次扫描）时才回落到它 —— 两者取更晚者，等价于「只扫没扫过的部分」。
            String watermark = readWatermark(sessionId);
            GraphitiScanMessage message = new GraphitiScanMessage(
                    UUID.randomUUID().toString(), sessionId, String.valueOf(userId), watermark);
            rabbitTemplate.convertAndSend("", GraphitiMqConfig.QUEUE_SCAN_DELAY, message);
            log.info("Graphiti 扫描已调度（{}ms 后静默触发） | sessionId={}, msgId={}",
                    GraphitiMqConfig.SCAN_DELAY_MS, sessionId, message.msgId());
        } catch (Exception e) {
            // MQ 不可用时静默降级：记忆沉淀非主流程，对话功能不受影响
            log.warn("Graphiti 扫描调度失败，本轮跳过记忆沉淀 | sessionId={}", sessionId, e);
        }
    }

    /**
     * 高危快通道（P4-B）：命中词表则<b>立即投递</b>，绕过 5 分钟 delay 队列。
     *
     * <p>为什么它必须存在：健康 / 禁忌类事实（过敏、疾病、硬约束）的<b>时效价值不对称</b> ——
     * 若等到会话静默 5 分钟才沉淀，用户下一句可能已经需要它了。而这五类事实天然带强关键词，
     * 一个纯规则词表就够，<b>零 LLM 调用、毫秒级</b>，因此可以放在落库主链路里同步跑。
     *
     * <p>四条约束：
     * <ol>
     *   <li><b>永不影响主链路</b>：本方法不抛异常，任何失败都只降级记日志 ——
     *       该轮仍会被 5 分钟常规链路兜住（所以这里失败不是「丢记忆」，只是「不准时」）。</li>
     *   <li><b>不是二选一</b>：调用方在命中后<b>照旧</b>武装常规扫描，因此同一条消息必然被投两次。
     *       这是设计承认的代价，防重由 {@code GraphitiWriteConsumer} 的 DB CAS 占位承担（铁律 1）。</li>
     *   <li><b>只代表优先投递，不代表一定入图</b>：入图与否仍由 {@code MemorySignalJudge} 决定
     *       （注意 regular 链路里判否会写审计表；快通道的消息若在常规链路被判否，
     *       一次重复投递即被审计表收敛，不会形成循环）。</li>
     *   <li><b>必须显式 setPriority(10)</b>：优先级是<b>消息属性</b>不是队列属性，
     *       不设就落到 0，优先级队列形同虚设。</li>
     * </ol>
     *
     * <p>⚠️ {@code timestamp} 用「此刻」而非查库取 {@code created_at}：这一轮刚刚发生，
     * 二者差值在毫秒级；为它多打一次查库往返（还可能是 N+1）不值得。
     *
     * @param sessionId     会话主键（仅日志与来源说明）
     * @param userMessageId 本轮 user 行的 {@code chat_message.id}（确认锚点来源）
     * @param content       本轮 user 原文
     * @param userId        用户主键（为空则不投：group_id 推不出来）
     */
    public void dispatchHighRiskNow(String sessionId, String userMessageId, String content, Long userId) {
        if (!properties.getWrite().isEnabled()) {
            return;
        }
        if (userId == null || userMessageId == null || userMessageId.isBlank()) {
            return;
        }
        if (!highRiskLexicon.hit(content)) {
            return;
        }
        try {
            String hitWords = String.join("、", highRiskLexicon.matched(content));
            String trimmed = content.strip();
            String payload = trimmed.length() <= GraphitiWriteMessage.CONTENT_CHAR_BUDGET
                    ? trimmed
                    : trimmed.substring(0, GraphitiWriteMessage.CONTENT_CHAR_BUDGET) + "…";
            // source_description 是人话式来源说明（前端会展示）—— 不写技术标记，顺带记录命中词
            GraphitiWriteMessage message = GraphitiWriteMessage.highRisk(
                    userMessageId, sessionId, String.valueOf(userId), payload,
                    LocalDateTime.now().toString(),
                    "用户消息（命中高危词：" + hitWords + "，提前投递）");
            rabbitTemplate.convertAndSend(GraphitiMqConfig.EXCHANGE_WRITE, GraphitiMqConfig.ROUTING_WRITE, message,
                    msg -> {
                        msg.getMessageProperties().setPriority(message.priority());
                        return msg;
                    });
            log.info("★ 高危快通道提前投递 | sessionId={}, messageId={}, 命中词={}",
                    sessionId, userMessageId, hitWords);
        } catch (Exception e) {
            log.warn("高危快通道投递失败（该轮仍会走 5 分钟常规链路，只是不准时） | sessionId={}, messageId={}",
                    sessionId, userMessageId, e);
        }
    }

    /**
     * 解除武装锁 —— <b>只允许在消费成功的路径上调用</b>。
     *
     * <p>★ 有条件释放是对旧链隐患 3 的结构性修复：消费失败的轮次不解除武装，
     * 让 TTL 兜底；同时水位保证下次仍能从那一点继续扫。
     */
    public void releaseArmLock(String sessionId) {
        try {
            redisTemplate.delete(ARMED_KEY_PREFIX + sessionId);
        } catch (Exception e) {
            log.warn("解除 Graphiti 扫描武装锁失败（TTL 将自动兜底） | sessionId={}", sessionId, e);
        }
    }

    /** 读取会话水位（{@code null} 表示从未扫描过）。 */
    public String readWatermark(String sessionId) {
        try {
            return redisTemplate.opsForValue().get(WATERMARK_KEY_PREFIX + sessionId);
        } catch (Exception e) {
            log.warn("读取 Graphiti 扫描水位失败，按无水处理 | sessionId={}", sessionId, e);
            return null;
        }
    }

    /**
     * 推进水位到「本轮扫过的最大业务时刻」。
     *
     * <p>★ 熔断态下<b>仍然要推进水位</b>（§3.3 ⑤）：否则下一轮会把同一批消息重新判定一次。
     * 被跳过的消息 {@code dispatched_at} 保持 NULL，由 {@code GraphitiReplayJob} 承接补投 ——
     * 「延迟 ≠ 丢弃」靠的就是这个分工。
     *
     * @param maxCreatedAt 本轮扫过的最大 {@code chat_message.created_at}
     */
    public void advanceWatermark(String sessionId, LocalDateTime maxCreatedAt) {
        if (maxCreatedAt == null) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(
                    WATERMARK_KEY_PREFIX + sessionId, maxCreatedAt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        } catch (Exception e) {
            log.warn("推进 Graphiti 扫描水位失败（下轮会重复判定同一批，无正确性影响） | sessionId={}", sessionId, e);
        }
    }
}
