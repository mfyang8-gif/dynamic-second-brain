package com.yangmf.mini_nodepad.mq;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

/**
 * 语义扫描 Producer：每轮对话落库成功后为 session "武装"一次延时扫描。
 * <p>
 * 冷却锁（盲区 F）：Redis setnx armed:{sessionId} TTL 与延迟窗口等长，已武装则跳过——
 * 把"N 轮对话 N 次冗余 LLM 分析"收敛为"会话静默 5 分钟后仅扫描一次"。
 * 发送失败仅降级记日志，绝不影响 persistMessages 主链路。
 */
@Slf4j
@Component
public class SemanticScanProducer {

    private static final String ARMED_KEY_PREFIX = "semantic:scan:armed:";

    private final StringRedisTemplate redisTemplate;
    private final RabbitTemplate rabbitTemplate;

    public SemanticScanProducer(StringRedisTemplate redisTemplate, RabbitTemplate rabbitTemplate) {
        this.redisTemplate = redisTemplate;
        this.rabbitTemplate = rabbitTemplate;
    }

    public void armSessionScan(String sessionId, Long userId) {
        try {
            Boolean armed = redisTemplate.opsForValue().setIfAbsent(
                    armedKey(sessionId), "1", Duration.ofMillis(RabbitSemanticConfig.SCAN_DELAY_MS));
            if (!Boolean.TRUE.equals(armed)) {
                log.debug("语义扫描已武装，跳过重复调度 | sessionId={}", sessionId);
                return;
            }
            SemanticScanMessage message = new SemanticScanMessage(
                    UUID.randomUUID().toString(), sessionId, userId);
            // 默认交换机（""）按路由键=队列名直投延时 parked 队列
            rabbitTemplate.convertAndSend("", RabbitSemanticConfig.QUEUE_DELAY, message);
            log.info("语义扫描已调度（{}ms 后静默触发） | sessionId={}, msgId={}",
                    RabbitSemanticConfig.SCAN_DELAY_MS, sessionId, message.msgId());
        } catch (Exception e) {
            // MQ 不可用（如服务器未就绪）时静默降级：习惯沉淀非主流程，对话功能不受影响
            log.warn("语义扫描调度失败，本轮跳过习惯沉淀 | sessionId={}", sessionId, e);
        }
    }

    /** 消费完成后解除武装，允许会话新一轮静默期重新调度 */
    public void releaseArmLock(String sessionId) {
        try {
            redisTemplate.delete(armedKey(sessionId));
        } catch (Exception e) {
            log.warn("解除扫描武装锁失败（TTL 将自动兜底） | sessionId={}", sessionId, e);
        }
    }

    private String armedKey(String sessionId) {
        return ARMED_KEY_PREFIX + sessionId;
    }
}
