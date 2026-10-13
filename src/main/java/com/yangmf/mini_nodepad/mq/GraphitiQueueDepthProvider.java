package com.yangmf.mini_nodepad.mq;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.stereotype.Component;

/**
 * 背压熔断的信号数据源（B1，§3.10）。
 *
 * <p>它只做一件事：把「消息此刻待在哪个物理容器里」变成两个数字 ——
 * {@code write} 与三个 {@code retry} 队列的 <b>ready 之和</b>。
 *
 * <p>★★ 为什么必须是 MQ 物理积压，而不是 MySQL 里的某个计数：
 * 熔断器的第一版设计用的是「未确认数」（{@code dispatched_at < now-15min AND synced_at IS NULL}），
 * 而补丁 1 规定「可重试失败必须回滚 {@code dispatched_at}」——
 * 于是 Graphiti 全挂时，<b>每一条消息都被回滚</b>，未确认数趋近 0，
 * 熔断器会报「系统无比健康」并继续猛灌（暗雷 2，监控假死）。
 * 而回滚 {@code dispatched_at} <b>不改变消息在 MQ 里的位置</b>：
 * Graphiti 全挂时消息必然在三个 retry 队列里循环，积压之和<b>单调上涨、不可欺骗</b>。
 *
 * <p>一句话：<b>用「消息在哪个物理容器里」判断健康，不用「数据库里某个字段的值」判断健康。</b>
 *
 * <p>⚠️ <b>量纲诚实说明</b>：AMQP 的被动声明只返回 {@code ready}，<b>不含 unacked</b>。
 * 因为本链路的消费者固定 {@code concurrency = 1 / prefetch = 1}，
 * unacked 至多为 1，可忽略 —— 这个前提一旦被改动，本指标就不再等价于「真实待处理量」。
 *
 * <p>❌ <b>刻意不含</b> {@code graphiti.scan.delay.queue}：它恒定有 N 条在设计延时中，
 * 纳入会让基线噪声淹没真实信号。
 * ❌ <b>刻意不含</b> DLQ：DLQ 只在重试全部失败后才涨，等它涨数据已经丢了，反应太慢。
 */
@Slf4j
@Component
public class GraphitiQueueDepthProvider {

    private final AmqpAdmin amqpAdmin;

    public GraphitiQueueDepthProvider(AmqpAdmin amqpAdmin) {
        this.amqpAdmin = amqpAdmin;
    }

    /**
     * 计算「熔断信号」：{@code write} + 三个 {@code retry} 队列的 ready 之和。
     *
     * @return 积压总数；单个队列查询失败（不存在 / broker 抖动）时该项按 0 计，
     *         整体查询彻底失败时返回 {@code -1} —— 调用方必须把 {@code -1} 当作
     *         「信号不可用」，而<b>不是</b>当作「积压为 0」而误判系统健康。
     */
    public int totalReadyDepth() {
        String[] queues = {
                GraphitiMqConfig.QUEUE_WRITE,
                GraphitiMqConfig.QUEUE_RETRY_30S,
                GraphitiMqConfig.QUEUE_RETRY_2MIN,
                GraphitiMqConfig.QUEUE_RETRY_10MIN
        };
        int total = 0;
        boolean anySuccess = false;
        for (String queue : queues) {
            Integer depth = readyOf(queue);
            if (depth != null) {
                total += depth;
                anySuccess = true;
            }
        }
        return anySuccess ? total : -1;
    }

    /**
     * 单个队列的 ready 数。
     *
     * @return ready 数；队列不存在或查询异常时返回 {@code null}（调用方需区分「查不到」与「为 0」）
     */
    public Integer readyOf(String queue) {
        try {
            QueueInformation info = amqpAdmin.getQueueInfo(queue);
            if (info == null) {
                return null;
            }
            return info.getMessageCount();
        } catch (Exception e) {
            // 队列尚未声明（如消费者未启动）/ broker 抖动：不抛异常 —— 熔断器自身不该成为故障源
            log.debug("读取队列深度失败（按不可用处理） | queue={} | {}", queue, e.getMessage());
            return null;
        }
    }
}
