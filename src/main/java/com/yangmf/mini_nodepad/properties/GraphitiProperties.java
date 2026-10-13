package com.yangmf.mini_nodepad.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

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
