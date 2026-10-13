package com.yangmf.mini_nodepad.job;

import com.yangmf.mini_nodepad.mapper.GraphitiWriteStateMapper;
import com.yangmf.mini_nodepad.mq.GraphitiMqConfig;
import com.yangmf.mini_nodepad.mq.GraphitiWriteMessage;
import com.yangmf.mini_nodepad.pojo.dto.GraphitiPendingMessage;
import com.yangmf.mini_nodepad.properties.GraphitiProperties;
import com.yangmf.mini_nodepad.service.GraphitiBackpressureGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Graphiti 补投任务（B1，铁律 4「只延迟、不丢弃」的唯一执行者）。
 *
 * <p>它是<b>两类</b>数据的唯一补投责任人：
 * <ol>
 *   <li>背压熔断期被跳过的消息（{@code GraphitiScanConsumer} 第 ⑤ 步，
 *       {@code dispatched_at} 保持 NULL）；</li>
 *   <li>扫描消费者崩溃 / 进 DLQ 导致整轮漏投的消息。</li>
 * </ol>
 *
 * <p>★ <b>为什么必须与 {@code GraphitiConfirmJob} 拆开</b>（暗雷 1）：
 * {@code dispatched_at IS NULL} 有<b>三种</b>互不相同的语义 ——
 * ① 尚未投递（本任务的目标）② 判定器判否（已进审计表）
 * ③ 毒消息 / 重试耗尽（已进审计表）。
 * 若把「未投递」与「已投递未确认」揉进同一个 {@code IS NULL} 条件，
 * 就会把判否的闲聊当成漏网之鱼<b>前门拦、后门灌</b>。
 * 拆开后本任务只看 {@code IS NULL 且不在审计表}，两者各自成为单向的。
 *
 * <p><b>为什么它<b>不</b>重新过一遍判定器</b>：
 * {@code GraphitiScanConsumer} 的流水线顺序是「判定 → 熔断检查 → 投递」，
 * 因此被熔断跳过的消息<b>已经判定通过</b>；重跑判定等于每条消息白烧一次 LLM，
 * 还会让「熔断期消息被重复判定」成为新的成本尖峰。
 * 唯一真正没经过判定的是「scan 崩溃时正在处理的那一批」，
 * 它们的投递属于<b>有意的保守放行</b> —— 与「判定器调用失败时放行」同一取向：
 * 宁可多投（噪音），不可丢真记忆。
 *
 * <p>★ <b>它永不写审计表</b>：审计表装的是「永不（重）投递」的终态，
 * 而补投失败的每一件都是<b>可恢复</b>的（MQ 不可用、Dashboard 不可达）。
 * 把可恢复写成终态，等于把「延迟」亲手改成「永久不投」——
 * 那正是本任务存在的意义被自己抹掉。
 *
 * <p>★ <b>熔断期主动跳过整轮</b>：补投的目的地就是那个已经积压的队列。
 * 一边熔断一边补投，等于熔断器自己给自己对冲。积压排空、熔断解除后，
 * 本任务会在下一轮把该投的全部投出去 —— 顺序天然正确，代价只是一段延迟。
 *
 * <p>⚠️ 投递<b>必须显式 {@code setPriority}</b>：优先级是消息属性不是队列属性，
 * 不设就落到队列默认值 0，会与扫描层的 priority=1 出现隐式不一致。
 */
@Slf4j
@Component
public class GraphitiReplayJob {

    private final GraphitiWriteStateMapper stateMapper;
    private final GraphitiBackpressureGuard backpressureGuard;
    private final RabbitTemplate rabbitTemplate;
    private final GraphitiProperties properties;

    public GraphitiReplayJob(GraphitiWriteStateMapper stateMapper,
                             GraphitiBackpressureGuard backpressureGuard,
                             RabbitTemplate rabbitTemplate,
                             GraphitiProperties properties) {
        this.stateMapper = stateMapper;
        this.backpressureGuard = backpressureGuard;
        this.rabbitTemplate = rabbitTemplate;
        this.properties = properties;
    }

    /**
     * 周期补投（默认 10 分钟一次）。
     *
     * <p>★ {@code initialDelay} 与 {@code fixedDelay} 同源：首次执行也延后一个周期，
     * 避免应用刚启动（Graphiti / MQ 可能都还没稳）就去扫一大段历史。
     */
    @Scheduled(fixedDelayString = "${graphiti.replay.interval-ms:600000}",
            initialDelayString = "${graphiti.replay.interval-ms:600000}")
    public void replay() {
        GraphitiProperties.Replay config = properties.getReplay();
        if (!config.isEnabled() || !properties.getWrite().isEnabled()) {
            return;
        }

        // ★ 熔断期整轮跳过（详见类注释）：不投递、不写审计表，只留下一行可观测日志。
        if (backpressureGuard.isFrozen()) {
            log.info("补投已跳过：背压熔断生效中（积压={}），待排空解冻后自动恢复 —— 延迟≠丢弃",
                    backpressureGuard.lastDepth());
            return;
        }

        int limit = Math.max(1, config.getBatchLimit());
        List<GraphitiPendingMessage> targets = stateMapper.selectReplayTargets(limit);
        if (targets.isEmpty()) {
            return;
        }

        int sent = 0;
        int failed = 0;
        int invalid = 0;

        for (GraphitiPendingMessage target : targets) {
            if (!isDeliverable(target)) {
                // 归属链断裂 / 业务时刻缺失：这里不写审计表（不该由补投层判终态），
                // 也不抛异常（一条坏数据不该拖垮整批）。下一轮仍会被捞出来，保持可观测。
                invalid++;
                continue;
            }
            if (publish(target)) {
                sent++;
            } else {
                failed++;
            }
        }

        log.info("Graphiti 补投完成 | 目标={}, 投出={}, 投递失败={}, 数据不完整={}, 熔断={}",
                targets.size(), sent, failed, invalid, backpressureGuard.isFrozen());

        if (targets.size() >= limit) {
            log.warn("补投已达单轮上限 {}，仍有待补投消息 —— 若连续多轮如此，"
                            + "说明消费能力持续低于积压增速，需检查 Graphiti 侧处理速率",
                    limit);
        }
    }

    /**
     * 把一条消息投回唯一写入口。
     *
     * <p>★ 这里<b>刻意不调用 {@code casOccupy}</b>：占位是投递方（消费者）在 POST 之前
     * 才拿的软锁，补投只是「把信封重新塞回邮筒」。真正防止重复 episode 的
     * 仍是写消费者里那一次 CAS —— 即使补投与扫描层同时投出同一条消息，
     * 第二个到达的也会在 CAS 处拿到 0 行并直接 ack 跳过。
     *
     * <p>★ 载荷必须带<b>原始业务时刻</b>（{@code created_at}）而非「补投时刻」：
     * Graphiti 侧双时间戳裁决依赖它，换成处理时刻会让历史对话被盖上错误的时序。
     */
    private boolean publish(GraphitiPendingMessage target) {
        try {
            GraphitiWriteMessage out = GraphitiWriteMessage.normal(
                    target.getMessageId(),
                    target.getSessionId(),
                    target.getUserId(),
                    budget(target.getContent()),
                    target.getCreatedAt().toString(),
                    "补投（熔断期跳过或扫描漏投，业务时刻 " + target.getCreatedAt() + "）");
            rabbitTemplate.convertAndSend(GraphitiMqConfig.EXCHANGE_WRITE, GraphitiMqConfig.ROUTING_WRITE, out,
                    msg -> {
                        msg.getMessageProperties().setPriority(out.priority());
                        return msg;
                    });
            log.debug("补投已发出 | messageId={}, createdAt={}", target.getMessageId(), target.getCreatedAt());
            return true;
        } catch (Exception e) {
            // 不写审计表：MQ 抖动是可恢复的，交给下一轮即可。
            log.warn("补投投递失败（不写审计表，下一轮重试） | messageId={}", target.getMessageId(), e);
            return false;
        }
    }

    /**
     * 投递前置校验。
     *
     * <p>三处判据都是「无法构造一条合法消息」的硬条件：
     * <ul>
     *   <li><b>userId 为空</b> —— {@code GraphitiWriteMessage} 的紧凑构造器会直接抛
     *       {@code IllegalArgumentException}（group_id 只能由它推导）；</li>
     *   <li><b>content 为空</b> —— 同上，空正文投过去只是污染图谱；</li>
     *   <li><b>createdAt 为空</b> —— 业务时刻无法重建。此处<b>刻意不用 {@code now()} 兜底</b>：
     *       那会把一条三个月前的历史消息盖成「刚刚发生」，比不投更糟。</li>
     * </ul>
     *
     * <p>注意：{@code userId} <b>非数字</b>不在这里拦 —— 那是「数据损坏」而非「数据缺失」，
     * 语义上属于写消费者的 POISON 判定，放它过去会由 {@code GraphitiWriteConsumer}
     * 写审计表进入终态（一轮之后即被 {@code NOT EXISTS} 排除），比在这里静默跳过更收敛。
     */
    private boolean isDeliverable(GraphitiPendingMessage target) {
        if (target == null) {
            return false;
        }
        if (target.getMessageId() == null || target.getMessageId().isBlank()) {
            return false;
        }
        if (target.getUserId() == null || target.getUserId().isBlank()) {
            log.error("补投跳过：消息归属链断裂（book.user_id 为空） | messageId={}, sessionId={}",
                    target.getMessageId(), target.getSessionId());
            return false;
        }
        if (target.getContent() == null || target.getContent().isBlank()) {
            log.error("补投跳过：正文为空 | messageId={}", target.getMessageId());
            return false;
        }
        if (target.getCreatedAt() == null) {
            log.error("补投跳过：业务时刻缺失（拒绝用补投时刻冒充） | messageId={}", target.getMessageId());
            return false;
        }
        return true;
    }

    /** 与扫描层共用的字符预算（单一真相见 {@link GraphitiWriteMessage#CONTENT_CHAR_BUDGET}）。 */
    private String budget(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.strip();
        return trimmed.length() <= GraphitiWriteMessage.CONTENT_CHAR_BUDGET
                ? trimmed
                : trimmed.substring(0, GraphitiWriteMessage.CONTENT_CHAR_BUDGET) + "…";
    }
}
