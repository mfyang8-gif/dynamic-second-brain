package com.yangmf.mini_nodepad.ai.component;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 双路检索可观测性埋点（用户 2026-10-12 裁决：日志 + 指标 + 用户提示，三者缺一不可）。
 *
 * <p>解决的问题是用户在审计里点名的"<b>降级时系统不报警、排障极难</b>"：
 * 旧方案里判卷器异常一律 fail-open，既不打点也不告警 —— 降级率悄悄爬升没人知道。
 * 现在每一次降级都落一条 WARN 日志 + 打一个计数器，趋势可在 {@code /actuator/metrics} 查。
 *
 * <p>暴露方式（用户拍板）：只暴露 {@code metrics,health}，并在
 * {@code WebMvcConfiguration} 里登记免登白名单 —— 内部监控指标不需要用户态登录。
 *
 * <p>指标定义（顺序即排查顺序）：
 * <ul>
 *   <li>{@code retrieval.dualpath.duration{path}} —— 两路各自耗时直方图（定位是哪路慢）</li>
 *   <li>{@code retrieval.dualpath.degraded{path,reason}} —— 降级计数（定位降级原因分布）</li>
 *   <li>{@code retrieval.pass.triggered{pass}} —— Pass1 / Pass2 触发次数（验两段式真的分段了）</li>
 *   <li>{@code retrieval.pass.ttft{pass}} —— 各段首字耗时（首字到底降没降，靠它说话）</li>
 *   <li>{@code retrieval.web.guard.dropped} —— 注入防护丢弃条数</li>
 *   <li>{@code retrieval.web.dedup.dropped} —— 语义去重丢弃条数</li>
 * </ul>
 */
@Slf4j
@Component
public class RetrievalMetrics {

    public static final String PATH_LOCAL = "local";
    public static final String PATH_WEB = "web";

    public static final String PASS_1 = "pass1";
    public static final String PASS_2 = "pass2";

    private final MeterRegistry registry;

    /** guard / dedup 的丢弃累计值：Micrometer 计数器本身可查，这里额外留一份进程内快照便于日志输出 */
    private final AtomicLong guardDropped = new AtomicLong();
    private final AtomicLong dedupDropped = new AtomicLong();

    public RetrievalMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** 记录一路检索的耗时与成败 */
    public void recordPath(String path, long costMs, boolean ok) {
        try {
            Timer.builder("retrieval.dualpath.duration")
                    .tag("path", path)
                    .tag("outcome", ok ? "ok" : "failed")
                    .register(registry)
                    .record(costMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.debug("指标写入失败（不影响主流程） | metric=retrieval.dualpath.duration", e);
        }
    }

    /**
     * 记录一次降级。reason 取值：
     * {@code qdrant_error / mcp_timeout / empty_result / all_empty / injection_dropped / no_query}
     */
    public void recordDegrade(String path, String reason) {
        try {
            registry.counter("retrieval.dualpath.degraded",
                    "path", path,
                    "reason", reason == null ? "unknown" : reason).increment();
        } catch (Exception e) {
            log.debug("指标写入失败（不影响主流程） | metric=retrieval.dualpath.degraded", e);
        }
        // 日志与指标双写：指标看趋势，日志看现场
        log.warn("双路检索降级 | path={}, reason={}", path, reason);
    }

    /** 记录一段流式作答被触发 */
    public void recordPassTriggered(String pass) {
        try {
            registry.counter("retrieval.pass.triggered", "pass", pass).increment();
        } catch (Exception e) {
            log.debug("指标写入失败 | metric=retrieval.pass.triggered", e);
        }
    }

    /** 记录某段的首字耗时（TTFT）：这是本方案唯一要回答的核心问题 */
    public void recordPassTtft(String pass, long ttftMs) {
        try {
            Timer.builder("retrieval.pass.ttft")
                    .tag("pass", pass)
                    .register(registry)
                    .record(ttftMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.debug("指标写入失败 | metric=retrieval.pass.ttft", e);
        }
    }

    /** 注入防护丢弃了 n 条来源 */
    public void recordGuardDropped(int n) {
        if (n <= 0) {
            return;
        }
        guardDropped.addAndGet(n);
        try {
            registry.counter("retrieval.web.guard.dropped").increment(n);
        } catch (Exception e) {
            log.debug("指标写入失败 | metric=retrieval.web.guard.dropped", e);
        }
    }

    /** 语义去重丢弃了 n 条来源 */
    public void recordDedupDropped(int n) {
        if (n <= 0) {
            return;
        }
        dedupDropped.addAndGet(n);
        try {
            registry.counter("retrieval.web.dedup.dropped").increment(n);
        } catch (Exception e) {
            log.debug("指标写入失败 | metric=retrieval.web.dedup.dropped", e);
        }
    }

    public long guardDroppedTotal() {
        return guardDropped.get();
    }

    public long dedupDroppedTotal() {
        return dedupDropped.get();
    }
}
