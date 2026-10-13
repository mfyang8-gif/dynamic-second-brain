package com.yangmf.mini_nodepad.mq;

import java.io.Serializable;

/**
 * Graphiti 扫描调度消息（B1 发现层的输入）。
 *
 * <p>链路位置：{@code ChatStreamService.persistMessages()} 落库成功后 → 冷却锁 setnx →
 * 投 {@code graphiti.scan.delay.queue}（TTL 300s）→ 过期死信 → {@code graphiti.scan.queue}。
 *
 * <p>两条工程约束：
 * <ol>
 *   <li><b>消费线程没有 HTTP 上下文</b>，{@code userId} 必须随消息体显式传递
 *       （禁止在消费线程里触碰 {@code BaseContext}）—— 与既有
 *       {@code SemanticScanMessage} 同因同解。</li>
 *   <li>★ <b>载荷里只用 JDK 基础类型与 String</b>：MQ 的
 *       {@code Jackson2JsonMessageConverter} 未必注册了 {@code JavaTimeModule}，
 *       塞 {@code LocalDateTime} 会退化成数组或直接抛错。时间一律用
 *       <b>ISO-8601 字符串</b>搬运，在消费侧显式解析。</li>
 * </ol>
 *
 * @param msgId     Producer 生成的幂等键（仅用于日志追踪；★ 真正的幂等靠水位与 CAS，
 *                  不再是 Redis 去重锁 —— 那把锁会「自己挡住自己」造成永久漏扫）
 * @param sessionId 待扫描会话
 * @param userId    用户主键（字符串形态：与 {@code book.user_id} 的 VARCHAR 同型）
 * @param watermark ★ 武装时刻的水位（ISO-8601 字符串，可为 null）。
 *                  消费侧优先用 Redis 里的最新水位；Redis 无值（首次扫描）时回落到它。
 *                  两者取「更晚」的那个，等价于「只扫没扫过的部分」。
 */
public record GraphitiScanMessage(String msgId,
                                 String sessionId,
                                 String userId,
                                 String watermark) implements Serializable {
}
