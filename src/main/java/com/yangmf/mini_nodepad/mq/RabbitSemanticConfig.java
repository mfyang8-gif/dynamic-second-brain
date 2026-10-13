package com.yangmf.mini_nodepad.mq;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.DefaultJackson2JavaTypeMapper;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.Jackson2JavaTypeMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 语义扫描 RabbitMQ 拓扑：TTL + DLX 原生延时方案（裁决 1：不赌延迟插件）。
 * <p>
 * Producer 经默认交换机直投 delay queue（无消费者、消息 TTL 5min）→ 过期死信进入
 * semantic.scan.exchange → 路由到 semantic.scan.queue 由 @RabbitListener 消费。
 * 只依赖 RabbitMQ 核心功能，100% 标准部署可跑通。
 */
@Configuration
public class RabbitSemanticConfig {

    /** 延时 parked 队列：不绑业务交换机，靠默认交换机按队列名路由 */
    public static final String QUEUE_DELAY = "semantic.scan.delay.queue";
    public static final String EXCHANGE_SCAN = "semantic.scan.exchange";
    public static final String ROUTING_SCAN = "scan";
    public static final String QUEUE_SCAN = "semantic.scan.queue";

    /** 会话静默冷却期：5 分钟（裁决 3/4） */
    public static final long SCAN_DELAY_MS = 300_000L;

    @Bean
    public Queue semanticScanDelayQueue() {
        return QueueBuilder.durable(QUEUE_DELAY)
                .ttl((int) SCAN_DELAY_MS)
                .deadLetterExchange(EXCHANGE_SCAN)
                .deadLetterRoutingKey(ROUTING_SCAN)
                .build();
    }

    @Bean
    public DirectExchange semanticScanExchange() {
        return new DirectExchange(EXCHANGE_SCAN, true, false);
    }

    @Bean
    public Queue semanticScanQueue() {
        return QueueBuilder.durable(QUEUE_SCAN).build();
    }

    @Bean
    public Binding semanticScanBinding(Queue semanticScanQueue, DirectExchange semanticScanExchange) {
        return BindingBuilder.bind(semanticScanQueue).to(semanticScanExchange).with(ROUTING_SCAN);
    }

    /**
     * JSON 消息转换器：__TypeId__ 受信包白名单限定在本项目 pojo 包，
     * 防恶意消息体携带伪造类名触发反序列化 gadget 攻击。
     */
    @Bean
    public Jackson2JsonMessageConverter jacksonMessageConverter() {
        Jackson2JsonMessageConverter converter = new Jackson2JsonMessageConverter();
        DefaultJackson2JavaTypeMapper typeMapper = new DefaultJackson2JavaTypeMapper();
        typeMapper.setTypePrecedence(Jackson2JavaTypeMapper.TypePrecedence.TYPE_ID);
        // ⚠️ 白名单是「包名精确 equals」而非前缀匹配（DefaultJackson2JavaTypeMapper.isTrustedPackage
        //    内部为 packageName.equals(trustedPackage)）。只写父包 "com.yangmf.mini_nodepad" 无法覆盖
        //    子包，SemanticScanMessage（com.yangmf.mini_nodepad.mq）会被判定为不可信 →
        //    IllegalArgumentException → MessageConversionException。
        //    又因 default-requeue-rejected=false，该消息被 reject 后静默丢弃且不重投，
        //    表现为「延时队列正常清空、scan.queue deliver+1 但 ack 恒为 0、消费者方法体从不执行」。
        //    今后每新增一个承载 MQ 载荷的包，都必须在此显式登记。
        typeMapper.addTrustedPackages(
                "com.yangmf.mini_nodepad.mq",
                "com.yangmf.mini_nodepad.pojo.dto");
        converter.setJavaTypeMapper(typeMapper);
        return converter;
    }
}
