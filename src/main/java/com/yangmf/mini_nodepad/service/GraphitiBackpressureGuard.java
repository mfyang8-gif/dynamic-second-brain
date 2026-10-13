package com.yangmf.mini_nodepad.service;

import com.yangmf.mini_nodepad.mq.GraphitiQueueDepthProvider;
import com.yangmf.mini_nodepad.properties.GraphitiProperties;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 背压熔断器（B1，补丁 7 + 暗雷 2）。
 *
 * <p>目标：当 Graphiti 的处理能力<b>已经触顶</b>时（物理极限 ≈ 2 条/分钟，单协程串行），
 * 主动<b>暂停常规投递</b>，把有限的消费能力留给高危事实 ——
 * 以「普通记忆延后」为代价保住核心链路。注意是<b>延后不是丢弃</b>：
 * 被跳过的消息 {@code dispatched_at} 保持 NULL，解冻后由 {@code GraphitiReplayJob} 补投。
 * 「延迟」能成立，全靠这个补投责任人存在（这是 rev.2 才补上的一环，
 * 没有它「延迟」会静默退化成「永久不投」）。
 *
 * <p>四件事必须一起看：
 * <table border="1">
 *   <caption>设计要点</caption>
 *   <tr><th>项</th><th>设计</th></tr>
 *   <tr><td>触发信号</td><td>★ 只认 MQ 物理积压（{@code write} + 三个 {@code retry} 的 ready 之和）</td></tr>
 *   <tr><td>触发阈值</td><td>{@code graphiti.backpressure.queue-depth-threshold}（默认 50）</td></tr>
 *   <tr><td>动作</td><td>冻结<b>常规</b>投递（扫描层第 ⑤ 步 ack 返回，不投递）；高危快通道仍放行</td></tr>
 *   <tr><td>恢复</td><td>积压降到「阈值 × 0.5」以下<b>且持续 3 分钟</b> → 自动解冻</td></tr>
 * </table>
 *
 * <p>⚠️ 熔断必须<b>可观测</b>：触发 / 恢复都打 WARN 并暴露 Micrometer Gauge
 * （{@code graphiti.backpressure.active}）。<b>静默的熔断 = 静默的数据延迟。</b>
 *
 * <p>⚠️ 一个如实承认的极限：若 Graphiti 彻底宕机，高危消息同样会积压在
 * {@code write.queue}（priority=10 排在队头）—— <b>背压保的是「优先级」，不是「可用性」</b>。
 * 可用性靠客户端超时降级承担。
 */
@Slf4j
@Component
public class GraphitiBackpressureGuard {

    private final GraphitiProperties properties;
    private final GraphitiQueueDepthProvider depthProvider;

    /** 冻结状态。volatile：由调度线程写、由 MQ 消费线程读。 */
    private volatile boolean frozen = false;

    /** 积压首次降到恢复线以下的时刻（0 = 尚未进入恢复观察期）。 */
    private volatile long belowThresholdSince = 0L;

    /** 最近一次观测到的积压深度（-1 = 信号不可用）。仅用于日志与看板。 */
    private volatile int lastDepth = -1;

    public GraphitiBackpressureGuard(GraphitiProperties properties,
                                     GraphitiQueueDepthProvider depthProvider,
                                     ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.properties = properties;
        this.depthProvider = depthProvider;
        MeterRegistry registry = meterRegistryProvider.getIfAvailable();
        if (registry != null) {
            registry.gauge("graphiti.backpressure.active", this, g -> g.isFrozen() ? 1.0 : 0.0);
            registry.gauge("graphiti.write.queue.depth", this, g -> Math.max(0, g.lastDepth()));
        }
    }

    /**
     * 周期评估（默认 30s 一次，四次轻量队列查询）。
     *
     * <p>★ 信号不可用（{@code depth < 0}）时<b>维持现状</b>：既不误判「健康」也不误判「故障」——
     * 熔断器自身不该成为故障源。
     */
    @Scheduled(fixedDelayString = "${graphiti.backpressure.evaluate-interval-ms:30000}",
            initialDelayString = "${graphiti.backpressure.evaluate-interval-ms:30000}")
    public void evaluate() {
        GraphitiProperties.Backpressure config = properties.getBackpressure();
        if (!config.isEnabled()) {
            if (frozen) {
                frozen = false;
                log.warn("背压熔断已被配置关闭，恢复常规投递");
            }
            return;
        }

        int depth = depthProvider.totalReadyDepth();
        this.lastDepth = depth;
        if (depth < 0) {
            log.debug("背压信号不可用（队列查询失败），本轮维持现状 | frozen={}", frozen);
            return;
        }

        int threshold = Math.max(1, config.getQueueDepthThreshold());
        long recoveryLine = (long) Math.floor(threshold * config.getRecoveryFactor());

        if (depth > threshold) {
            belowThresholdSince = 0L;
            if (!frozen) {
                frozen = true;
                log.warn("★ 背压熔断触发：冻结常规投递（高危快通道仍放行） | 积压={} > 阈值={} | "
                                + "被跳过的消息将由 GraphitiReplayJob 在解冻后补投（延迟≠丢弃）",
                        depth, threshold);
            }
            return;
        }

        if (depth < recoveryLine) {
            if (!frozen) {
                return;
            }
            long now = System.currentTimeMillis();
            if (belowThresholdSince == 0L) {
                belowThresholdSince = now;
                log.info("背压回落至恢复线以下，进入观察期 | 积压={} < {} | 需持续 {}ms",
                        depth, recoveryLine, config.getRecoveryHoldMs());
                return;
            }
            if (now - belowThresholdSince >= config.getRecoveryHoldMs()) {
                frozen = false;
                belowThresholdSince = 0L;
                log.warn("背压熔断解除：恢复常规投递 | 积压={} | 积压消息由 GraphitiReplayJob 逐步补投",
                        depth);
            }
            return;
        }

        // 处于「恢复线 ~ 阈值」之间：不稳定区，重置观察起点（避免抖动反复解冻）
        belowThresholdSince = 0L;
    }

    /** 常规投递是否已被冻结。高危快通道<b>不</b>受它影响。 */
    public boolean isFrozen() {
        return frozen;
    }

    /** 最近一次观测到的积压深度（-1 表示信号不可用）。仅用于看板。 */
    public int lastDepth() {
        return lastDepth;
    }
}
