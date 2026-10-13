package com.yangmf.mini_nodepad.mq;

import java.io.Serializable;

/**
 * Graphiti 单条投递消息（B1 投递层的输入，N=1）。
 *
 * <p>链路位置：{@code graphiti.scan.queue} 判定通过 → 逐条投 {@code graphiti.write.queue}
 * → {@code GraphitiWriteConsumer} → {@code POST /messages}。
 *
 * <p>★ <b>为什么 N=1 而不是批</b>（§3.9）：服务端 {@code ingest.py} 是<b>逐条入队</b>的
 * （每条 message 独立成一个 job），所以「一次 POST 带 3 条」与「单条投递 3 次」在
 * 图谱结果上<b>完全等价</b>，批的唯一收益是省几次毫秒级 HTTP 往返；
 * 而 N=1 换来「投递粒度 = 确认粒度 = {@code graphiti_dispatched_at} 更新粒度」三者对齐，
 * 不必回答「批内部分成功怎么算」，且失败隔离最彻底。
 *
 * <p>★ <b>priority 是消息属性，不是队列属性</b>：凡是我们自己构造 {@code Message}
 * 重新发布的地方（进重试队列、ReplayJob 补投），都必须显式
 * {@code setPriority(原优先级)} —— 否则高危消息失败一次就降级成平民，
 * 优先级队列形同虚设。
 *
 * <p>⚠️ 时间字段用 <b>ISO-8601 字符串</b>搬运（理由见 {@link GraphitiScanMessage} 注释），
 * 消费侧再转成 {@code OffsetDateTime} 交给 Graphiti —— 这是「历史对话不被盖上处理时刻」
 * 的物理保证（陷阱 4）。
 *
 * @param messageId         {@code chat_message.id}，同时是确认锚点 {@code "cm_" + messageId} 的来源
 * @param sessionId         会话主键（仅用于日志与排查）
 * @param userId            用户主键（字符串形态，投递时转数值型）
 * @param content           消息正文（已在上游按预算截断）
 * @param createdAtIso      业务发生时刻（ISO-8601，必须原样作为 Graphiti 的 {@code timestamp}）
 * @param sourceDescription ★ 人话式来源说明，前端会展示给用户 ——
 *                          禁止塞「【高危预警触发】」这类技术标记（陷阱 17）
 * @param attempt           已尝试次数（从 0 开始），决定退避档位
 * @param priority          优先级：常规 1、高危快通道 10（队列声明 x-max-priority=10）
 */
public record GraphitiWriteMessage(String messageId,
                                   String sessionId,
                                   String userId,
                                   String content,
                                   String createdAtIso,
                                   String sourceDescription,
                                   int attempt,
                                   int priority) implements Serializable {

    /** 常规投递优先级。 */
    public static final int PRIORITY_NORMAL = 1;

    /** 高危快通道优先级（命中高危词表时插队）。 */
    public static final int PRIORITY_HIGH_RISK = 10;

    /**
     * 单条正文的字符预算（上限 {@code GraphitiMessage.MAX_CONTENT_LENGTH} = 8000）。
     *
     * <p>★ 提到这里是为了<b>单一真相</b>：扫描层与补投层是两条独立进入
     * {@code graphiti.write.queue} 的路径，各自维护一个私有常量的话，
     * 改了一处另一处会静默分叉 —— 同一条消息经不同路径进来会被截成不同长度，
     * 而这类差异<b>不报错、不可观测</b>，只在人群里表现为「有时候记忆不完整」。
     */
    public static final int CONTENT_CHAR_BUDGET = 4000;

    public GraphitiWriteMessage {
        if (messageId == null || messageId.isBlank()) {
            throw new IllegalArgumentException("GraphitiWriteMessage.messageId 不能为空：确认锚点依赖它");
        }
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("GraphitiWriteMessage.userId 不能为空：group_id 只能由它推导");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("GraphitiWriteMessage.content 不能为空");
        }
        if (createdAtIso == null || createdAtIso.isBlank()) {
            throw new IllegalArgumentException(
                    "GraphitiWriteMessage.createdAtIso 不能为空：timestamp 缺失会让历史对话被盖成处理时刻");
        }
        if (priority <= 0) {
            priority = PRIORITY_NORMAL;
        }
    }

    /** 常规优先级投递（扫描层发现层使用）。 */
    public static GraphitiWriteMessage normal(String messageId, String sessionId, String userId,
                                              String content, String createdAtIso, String sourceDescription) {
        return new GraphitiWriteMessage(messageId, sessionId, userId, content, createdAtIso,
                sourceDescription, 0, PRIORITY_NORMAL);
    }

    /** 高危快通道投递（命中高危词表，绕过 5 分钟静默期立即投递）。 */
    public static GraphitiWriteMessage highRisk(String messageId, String sessionId, String userId,
                                                String content, String createdAtIso, String sourceDescription) {
        return new GraphitiWriteMessage(messageId, sessionId, userId, content, createdAtIso,
                sourceDescription, 0, PRIORITY_HIGH_RISK);
    }

    /**
     * 重投到下一档重试队列：attempt + 1，<b>并原样保留优先级</b>。
     *
     * <p>★ 这两个动作必须成对：只加 attempt 不保 priority，高危消息失败一次就降级；
     * 只保 priority 不加 attempt，会无限重试。
     */
    public GraphitiWriteMessage nextAttempt() {
        return new GraphitiWriteMessage(messageId, sessionId, userId, content, createdAtIso,
                sourceDescription, attempt + 1, priority);
    }

    /** 是否已达重试上限（应进 DLQ + 审计终态）。 */
    public boolean isExhausted() {
        return attempt >= com.yangmf.mini_nodepad.client.GraphitiErrorClassifier.MAX_ATTEMPT;
    }
}
