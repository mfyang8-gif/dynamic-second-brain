package com.yangmf.mini_nodepad.mq;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Graphiti 写入链 RabbitMQ 拓扑（Phase 4 · B1，§3.2）。
 *
 * <pre>
 * 落库成功 ─► armSessionScan()   [冷却锁 5min setnx，语义与旧链一致]
 *                  │ 默认交换机("")，rk = 队列名
 *      graphiti.scan.delay.queue   队列级 TTL 300s, DLX = graphiti.scan.dlx (rk=scan)
 *                  │ 过期死信
 *      graphiti.scan.dlx ──[rk=scan]──► graphiti.scan.queue
 *                                            │ GraphitiScanConsumer
 *                                            │ 异常 reject 不 requeue
 *                                            └─[rk=dead]──► graphiti.scan.dlq（可观测停泊）
 *                                            │ 逐条 N=1 投递（priority=1）
 *      GraphitiWriteMessage ───────────► graphiti.write.queue   ★ 唯一 Graphiti 写入口
 *                                        【x-max-priority = 10，高危插队】
 *                                        DLX = graphiti.write.exchange (rk=dead)
 *                                            │ GraphitiWriteConsumer
 *                                            ├─[rk=write]─► 重试三档（TTL 到期经 DLX 回到本队列）
 *                                            │    retry.30s ──► retry.2min ──► retry.10min
 *                                            ├─[rk=dead]──► graphiti.write.dlq
 *                                            └─ 成功 ──► ack（确认交给 GraphitiConfirmJob）
 * </pre>
 *
 * <p>三个「不做就会错」的设计点：
 *
 * <ol>
 *   <li><b>三个静态 TTL 队列，而不是一个队列挂多档 TTL</b>（陷阱 14）：
 *       RabbitMQ 的队列 TTL 是<b>队列级</b>属性，队首消息的 TTL 会挡住后面 TTL 更短的消息
 *       —— 即<b>队头阻塞</b>，单队列多档是错的。</li>
 *   <li><b>重试回到 {@code write.queue} 的那一跳交给 broker 的 TTL+DLX</b>：
 *       重试期间消费者线程立即释放（对比 {@code @Retryable} 的同线程循环会占住消费者，陷阱 15）。</li>
 *   <li><b>两级队列都配 DLX</b>：这是「不做 catch-all 吞异常」的配套 ——
 *       异常冒泡时消息必须有个<b>可观测的泊位</b>，否则在
 *       {@code default-requeue-rejected=false} 下就是静默消失。</li>
 * </ol>
 *
 * <p>⚠️ <b>队列参数一经创建不可改</b>：若 {@code graphiti.write.queue} 已存在且
 * {@code x-max-priority} 不同，{@code queue.declare} 会返回 406 PRECONDITION_FAILED。
 * 调参顺序见 {@code src/main/resources/db/phase4-b1-graphiti-write-chain.sql} 末尾的运维提示。
 * （本批次的队列<b>全部是新建</b>，不存在该风险；风险只出现在后续调参。）
 */
@Configuration
public class GraphitiMqConfig {

    // ==================== 扫描段（发现层） ====================

    /** 延时 parked 队列：不绑业务交换机，靠默认交换机按队列名路由。 */
    public static final String QUEUE_SCAN_DELAY = "graphiti.scan.delay.queue";
    /** 扫描段死信交换机（既负责「延时到期投递」，也负责「消费失败停泊」）。 */
    public static final String EXCHANGE_SCAN_DLX = "graphiti.scan.dlx";
    /** 扫描路由键（延时消息 → 扫描队列）。 */
    public static final String ROUTING_SCAN = "scan";
    /** 扫描消费队列（发现层的入口）。 */
    public static final String QUEUE_SCAN = "graphiti.scan.queue";
    /** 扫描段死信队列：本轮扫描异常时的可观测泊位。 */
    public static final String QUEUE_SCAN_DLQ = "graphiti.scan.dlq";
    /** 死信路由键（两个交换机共用，语义 = 「该消息失败了」）。 */
    public static final String ROUTING_DEAD = "dead";

    /** 会话静默冷却期：5 分钟（与旧链一致，语义不变）。 */
    public static final long SCAN_DELAY_MS = 300_000L;

    // ==================== 投递段（唯一写入口） ====================

    /** ★ 唯一 Graphiti 写入口。 */
    public static final String QUEUE_WRITE = "graphiti.write.queue";
    /** 写段直连交换机（重试队列死信回写队列的中转）。 */
    public static final String EXCHANGE_WRITE = "graphiti.write.exchange";
    /** 写段路由键（回到唯一写入口）。 */
    public static final String ROUTING_WRITE = "write";
    /** 死信队列：毒消息 / 重试耗尽的物理泊位（终态的第二处证据，第一处是审计表）。 */
    public static final String QUEUE_WRITE_DLQ = "graphiti.write.dlq";

    /** 重试队列（静态 TTL，三档）。 */
    public static final String QUEUE_RETRY_30S = "graphiti.write.retry.30s.queue";
    public static final String QUEUE_RETRY_2MIN = "graphiti.write.retry.2min.queue";
    public static final String QUEUE_RETRY_10MIN = "graphiti.write.retry.10min.queue";

    public static final int RETRY_TTL_30S_MS = 30_000;
    public static final int RETRY_TTL_2MIN_MS = 120_000;
    public static final int RETRY_TTL_10MIN_MS = 600_000;

    /** 专用监听容器工厂名（prefetch=1 / concurrency=1，不影响全局 prefetch）。 */
    public static final String LISTENER_FACTORY = "graphitiListenerContainerFactory";

    /**
     * 优先级队列上限。
     *
     * <p>常规消息 priority = 1，高危快通道 priority = 10。
     * ⚠️ 优先级<b>只在队列积压时生效</b>：消费者空闲时消息一到就被推走，优先级无意义 ——
     * 它与背压熔断是一对，单独做没有意义。
     */
    public static final int WRITE_MAX_PRIORITY = 10;

    // ==================== 扫描段声明 ====================

    @Bean
    public Queue graphitiScanDelayQueue() {
        return QueueBuilder.durable(QUEUE_SCAN_DELAY)
                .ttl((int) SCAN_DELAY_MS)
                .deadLetterExchange(EXCHANGE_SCAN_DLX)
                .deadLetterRoutingKey(ROUTING_SCAN)
                .build();
    }

    @Bean
    public DirectExchange graphitiScanDlx() {
        return new DirectExchange(EXCHANGE_SCAN_DLX, true, false);
    }

    @Bean
    public Queue graphitiScanQueue() {
        return QueueBuilder.durable(QUEUE_SCAN)
                .deadLetterExchange(EXCHANGE_SCAN_DLX)
                .deadLetterRoutingKey(ROUTING_DEAD)
                .build();
    }

    @Bean
    public Queue graphitiScanDlq() {
        return QueueBuilder.durable(QUEUE_SCAN_DLQ).build();
    }

    @Bean
    public Binding graphitiScanBinding(Queue graphitiScanQueue, DirectExchange graphitiScanDlx) {
        return BindingBuilder.bind(graphitiScanQueue).to(graphitiScanDlx).with(ROUTING_SCAN);
    }

    @Bean
    public Binding graphitiScanDlqBinding(Queue graphitiScanDlq, DirectExchange graphitiScanDlx) {
        return BindingBuilder.bind(graphitiScanDlq).to(graphitiScanDlx).with(ROUTING_DEAD);
    }

    // ==================== 投递段声明 ====================

    @Bean
    public DirectExchange graphitiWriteExchange() {
        return new DirectExchange(EXCHANGE_WRITE, true, false);
    }

    @Bean
    public Queue graphitiWriteQueue() {
        return QueueBuilder.durable(QUEUE_WRITE)
                .withArgument("x-max-priority", WRITE_MAX_PRIORITY)
                .deadLetterExchange(EXCHANGE_WRITE)
                .deadLetterRoutingKey(ROUTING_DEAD)
                .build();
    }

    @Bean
    public Binding graphitiWriteBinding(Queue graphitiWriteQueue, DirectExchange graphitiWriteExchange) {
        return BindingBuilder.bind(graphitiWriteQueue).to(graphitiWriteExchange).with(ROUTING_WRITE);
    }

    @Bean
    public Queue graphitiWriteDlq() {
        return QueueBuilder.durable(QUEUE_WRITE_DLQ).build();
    }

    @Bean
    public Binding graphitiWriteDlqBinding(Queue graphitiWriteDlq, DirectExchange graphitiWriteExchange) {
        return BindingBuilder.bind(graphitiWriteDlq).to(graphitiWriteExchange).with(ROUTING_DEAD);
    }

    @Bean
    public Queue graphitiRetry30sQueue() {
        return retryQueue(QUEUE_RETRY_30S, RETRY_TTL_30S_MS);
    }

    @Bean
    public Queue graphitiRetry2minQueue() {
        return retryQueue(QUEUE_RETRY_2MIN, RETRY_TTL_2MIN_MS);
    }

    @Bean
    public Queue graphitiRetry10minQueue() {
        return retryQueue(QUEUE_RETRY_10MIN, RETRY_TTL_10MIN_MS);
    }

    /**
     * 构造一个静态 TTL 重试队列：TTL 到期即死信回 {@code graphiti.write.queue}。
     *
     * <p>★ 死信回到 {@code write.queue} 时，消息属性（含 priority）是否被 broker 保留
     * 需要实测（附录 D #11）—— 不能假设。若实测发现丢失，重试链路必须改为手动 republish
     * 并显式 setPriority。
     */
    private Queue retryQueue(String name, int ttlMs) {
        return QueueBuilder.durable(name)
                .ttl(ttlMs)
                .deadLetterExchange(EXCHANGE_WRITE)
                .deadLetterRoutingKey(ROUTING_WRITE)
                .build();
    }

    /**
     * 写入链专用监听容器工厂。
     *
     * <p>★ 为什么不改全局 {@code spring.rabbitmq.listener.simple.prefetch}：
     * 旧链（{@code semantic.scan.queue}）也在用全局工厂，把它降到 1 会顺带改变旧链的吞吐特征。
     * 新链需要 {@code prefetch=1} 是因为它依赖「unacked ≤ 1」这一量纲假设 ——
     * 背压熔断的信号只统计 ready、不含 unacked。
     */
    @Bean(LISTENER_FACTORY)
    public SimpleRabbitListenerContainerFactory graphitiListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setPrefetchCount(1);
        factory.setConcurrentConsumers(1);
        factory.setMaxConcurrentConsumers(1);
        return factory;
    }

    /**
     * 按退避档位取重试队列名。
     *
     * @param level 0 = 30s，1 = 2min，2 = 10min
     * @return 队列名；越界时取最长档（保守：宁可等久一点，也不要制造高频重试）
     */
    public static String retryQueueOf(int level) {
        return switch (level) {
            case 0 -> QUEUE_RETRY_30S;
            case 1 -> QUEUE_RETRY_2MIN;
            default -> QUEUE_RETRY_10MIN;
        };
    }
}
