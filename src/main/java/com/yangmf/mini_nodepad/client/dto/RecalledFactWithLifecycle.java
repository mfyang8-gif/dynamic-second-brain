package com.yangmf.mini_nodepad.client.dto;

import java.time.OffsetDateTime;

/**
 * 召回结果（<b>含已作废</b>）—— 仅供语义化旁路使用。
 *
 * <p>三个真实消费者（也仅有这三个）：
 * <ol>
 *   <li>排障：要看作废边才能定位「为什么这条记忆不生效」；</li>
 *   <li>「我的记忆」页面的<b>变更历史</b>（走独立只读接口，不经过 {@code recallActive}）；</li>
 *   <li>双时间戳验收（测试用例需要读作废事实来断言裁决确实发生了）。</li>
 * </ol>
 * 三者都是「特定调用点 + 特定用途」，没有任何一个是「运行时按配置切换」——
 * 这也是不做 {@code include-invalidated: true} 全局开关的核心原因：
 * 一个影响全局正确性的开关，一旦被误开且忘关，失效是<b>静默的</b>（无异常、无日志、无告警）。
 *
 * <p>★ 本类型与 {@link RecalledFact} <b>故意不通用</b>：若给 {@code RecalledFact}
 * 加一个可空字段来兼作旁路，那「类型系统即防呆」的保证会被撤销 ——
 * 拿到结果的代码将无法区分它是过滤过的还是没过滤的。
 */
public record RecalledFactWithLifecycle(

        String factId,

        /** 事实陈述原文（已过 PromptSanitizer）。 */
        String statement,

        /** 业务生效时刻，可空。 */
        OffsetDateTime validAt,

        /** 业务失效时刻，可空；非空即已作废。 */
        OffsetDateTime invalidAt,

        /** 系统账本失效时刻，可空（不参与有效性判定）。 */
        OffsetDateTime expiredAt,

        /** 生命周期状态。 */
        Lifecycle status) {

    /** 生命周期状态。 */
    public enum Lifecycle {
        /** 当前有效（{@code invalidAt == null}）。 */
        ACTIVE,
        /** 已被新事实取代。 */
        SUPERSEDED
    }

    /** 由原始响应构造：状态判定只看 {@code invalidAt}（§2.6）。 */
    public static RecalledFactWithLifecycle of(String factId,
                                               String statement,
                                               OffsetDateTime validAt,
                                               OffsetDateTime invalidAt,
                                               OffsetDateTime expiredAt) {
        return new RecalledFactWithLifecycle(factId, statement, validAt, invalidAt, expiredAt,
                invalidAt == null ? Lifecycle.ACTIVE : Lifecycle.SUPERSEDED);
    }
}
