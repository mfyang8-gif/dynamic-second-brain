package com.yangmf.mini_nodepad.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Graphiti 记忆微服务配置（Phase 4 · B0 契约层）。
 *
 * <p>本类是 B0 批次唯一的「有外部副作用」的产物 —— 它把 yaml 里的开关、超时与调参项
 * 一次性绑定成型，后续 B1（写入链）/ B2（召回链）只消费不新增配置键。
 *
 * <p>三个设计要点：
 * <ul>
 *   <li><b>开关默认全关</b>：B0 阶段没有任何消费方，默认 {@code false} 可确保
 *       即使 B1 半成品上线也不会误触发（灰度三开关见设计文档 §5.1）。</li>
 *   <li><b>超时按调用用途分档</b>（§2.5）：读操作必须远短于写操作 ——
 *       召回阻塞 SSE 首字，写入只在 MQ 线程里等 202。</li>
 *   <li><b>宽拉取倍数可调</b>（补丁 2）：影子期要按「过滤后不足 limit」的实际频次调参，
 *       硬编码就得改代码。</li>
 * </ul>
 *
 * <p>⚠️ 放置位置与设计文档 §7.1 的 {@code config/GraphitiProperties} 略有出入：
 * 本工程 {@code @ConfigurationProperties} 统一在 {@code properties} 包
 * （{@code QdrantProperties} / {@code JwtProperties} / {@code AliOssProperties} / {@code PandocProperties}），
 * 此处跟随工程既有约定，避免同类配置分居两处。
 */
@Data
@Component
@ConfigurationProperties(prefix = "graphiti")
public class GraphitiProperties {

    /** 总开关。B0 无消费方；B1 起按批次逐层打开。 */
    private boolean enabled = false;

    /** 服务端根地址（VM 容器 memory_graphiti，非桥接网络下仅本机与宿主机可达）。 */
    private String baseUrl = "http://192.168.88.130:8088";

    private Write write = new Write();

    private Recall recall = new Recall();

    private Timeout timeout = new Timeout();

    private Confirm confirm = new Confirm();

    private Replay replay = new Replay();

    private Backpressure backpressure = new Backpressure();

    /** 写入链（MQ 消费侧）配置。 */
    @Data
    public static class Write {

        /** 写入链开关：控制生产者是否投递、消费者是否消费。 */
        private boolean enabled = false;

        /**
         * 实质判定（MemorySignalJudge）开关。
         * 关闭时全部走规则快通道（高危词表 + 硬规则），不再调用 LLM 判定。
         */
        private boolean judgeEnabled = false;

        /**
         * 高危词表（纯规则，不调 LLM）：命中的轮次<b>绕过 5 分钟静默期立即投递</b>，
         * 并以 {@code priority = 10} 插队。
         *
         * <p>为什么需要：健康/禁忌类事实（过敏、疾病、硬约束）若等到会话静默 5 分钟
         * 才沉淀，用户下一句就可能已经需要它了 —— 这类事实的时效价值远高于普通偏好。
         *
         * <p>⚠️ 词表是<b>召回侧</b>的粗筛，不是判定替代品：命中只代表「优先投递」，
         * 不代表「一定入图」（仍要过 MemorySignalJudge）。
         */
        private List<String> highRiskLexicon = new ArrayList<>();

        /** 单轮扫描最多处理的 user 轮次数（防一次扫描把整段历史压给 LLM 判定）。 */
        private int scanWindowLimit = 50;
    }

    /** 对账任务（GraphitiConfirmJob）配置 —— 只管「已投递但未确认落库」。 */
    @Data
    public static class Confirm {

        private boolean enabled = true;

        /** 执行间隔（毫秒）。默认 5 分钟。 */
        private long intervalMs = 300_000L;

        /** 单轮最多处理的确认目标数。 */
        private int batchLimit = 100;

        /**
         * 未命中累计轮次上限；达上限即写审计表（CONFIRM_TIMEOUT）并停止重投。
         *
         * <p>★ 没有这个上限，「窗口足够大、但那条消息就是永远落不了库」的消息
         * 会被每 5 分钟重投一次、永远重投下去 —— 上限 + 隔离是防无限重投的另一半。
         */
        private int maxRounds = 8;

        /** 重投开始前的宽限轮次（落库本身 15~45s，别和落库延迟赛跑）。 */
        private int replayAfterRounds = 2;

        /**
         * 动态 {@code last_n} 的保底值。
         *
         * <p>★ 严禁硬编码定值代替动态计算：用户在 5 分钟内连发 30 条短消息，
         * 目标就会被挤出窗口 → 对账误判「投递失败」→ 重投 → 又「失败」→ 无限重投。
         */
        private int minLastN = 50;

        /** 动态 {@code last_n} 的封顶值；超过则打 WARN（说明窗口设错或有异常流量）。 */
        private int maxLastN = 500;
    }

    /** 补投任务（GraphitiReplayJob）配置 —— 只管「该投但没投出去」。 */
    @Data
    public static class Replay {

        private boolean enabled = true;

        /** 执行间隔（毫秒）。默认 10 分钟。 */
        private long intervalMs = 600_000L;

        /** 单轮最多补投条数。 */
        private int batchLimit = 200;
    }

    /** 背压熔断（GraphitiBackpressureGuard）配置。 */
    @Data
    public static class Backpressure {

        private boolean enabled = true;

        /** 评估间隔（毫秒）。默认 30 秒。 */
        private long evaluateIntervalMs = 30_000L;

        /**
         * 触发阈值：{@code write} + 三个 {@code retry} 队列的 ready 之和。
         *
         * <p>★★ 信号源<b>只允许</b>是 MQ 物理积压。用 MySQL 的「未确认数」做触发是错的：
         * 可重试失败会回滚 {@code dispatched_at}，Graphiti 全挂时每一条都被回滚
         * → 未确认数趋近 0 → <b>熔断器会报「系统无比健康」并继续猛灌</b>（暗雷 2）。
         *
         * <p>阈值必须显著小于「最大重试窗（30s + 2min + 10min ≈ 12.5min）内能被消费掉的消息量」——
         * 否则消息会在熔断生效之前就耗尽重试、进 DLQ（终态）。
         */
        private int queueDepthThreshold = 50;

        /** 恢复系数：积压降到「阈值 × 该系数」以下且持续一段时间才解冻。 */
        private double recoveryFactor = 0.5;

        /** 恢复所需的持续时长（毫秒）。默认 3 分钟。 */
        private long recoveryHoldMs = 180_000L;
    }

    /** 召回链（在线路径）配置。 */
    @Data
    public static class Recall {

        /** 召回链开关：关闭时主链路完全不注入记忆（等价于 Phase 4 之前的行为）。 */
        private boolean enabled = false;

        /**
         * ★ 宽拉取倍数（补丁 2）—— HTTP 层请求 {@code max_facts = limit × fetchFactor}，
         * 客户端过滤已作废事实后再截断回 {@code limit}。
         *
         * <p>为什么必须放大：Graphiti 的 {@code /search} <b>不过滤 invalid_at</b>，
         * 用户改口越频繁，「匹配度最高的 N 条已全部作废」的概率越高 ——
         * 此时若不做宽拉取，真正有效的那条（匹配度第 N+1）根本没被拉回来，
         * 后果不是「精度下降」而是<b>召回为空</b>，恰好在最该生效的场景失效。
         *
         * <p>约束：不得小于 1；设计基线为 4。若影子期观测到「过滤后不足 limit」频次 > 5%，再上调。
         */
        private int fetchFactor = 4;

        /**
         * L1「未沉淀原文」时间窗兜底（分钟），见设计文档 §4.1 风险二。
         * {@code MessageWindowChatMemory.maxMessages = 6} 是<b>条数</b>窗口不是时间窗，
         * 密集对话 5 分钟即挤满，因此需要按时间窗兜底回捞尚未沉淀进图谱的原文。
         */
        private int unsettledWindowMinutes = 15;
    }

    /** 超时分档（§2.5）。单位毫秒。 */
    @Data
    public static class Timeout {

        /** 召回连接超时（在线路径）。 */
        private int recallConnectMs = 1000;
        /** 召回读超时（在线路径，有硬预算，超时即降级为「无记忆」）。 */
        private int recallReadMs = 2000;
        /** 写入连接超时（MQ 路径，不阻塞用户）。 */
        private int writeConnectMs = 2000;
        /** 写入读超时：只需 202 到手。 */
        private int writeReadMs = 5000;
        /** 对账轮询连接超时（定时任务，不阻塞任何用户路径）。 */
        private int confirmConnectMs = 1000;
        /** 对账轮询读超时。 */
        private int confirmReadMs = 3000;
    }

    /**
     * 生效的拉取倍数（带下限保护）。
     * 配置写成 0 或负数时会被拉回 1 —— 否则 {@code max_facts = 0} 会静默返回空结果。
     */
    public int effectiveFetchFactor() {
        return Math.max(1, getRecall().getFetchFactor());
    }
}
