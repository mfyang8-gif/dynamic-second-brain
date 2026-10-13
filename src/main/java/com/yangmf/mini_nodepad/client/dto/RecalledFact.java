package com.yangmf.mini_nodepad.client.dto;

import java.time.OffsetDateTime;

/**
 * 召回结果（已过滤 + 已消毒 + 已截断的「有效事实」）。
 *
 * <p>★ <b>结构上就没有 {@code invalidAt} / {@code expiredAt} 字段 —— 类型系统即防呆。</b>
 * 过滤发生在 {@code GraphitiMemoryClient#recallActive()} 内部（唯一出口），
 * 而不是靠每个调用点自觉：拿到本类型就意味着「这条一定当前有效」，
 * 不存在「忘了过滤」或「过滤了但调用方又用时间戳自己判一遍判错」的可能。
 *
 * <p>想拿已作废事实必须显式调用语义化旁路
 * {@code recallIncludingInvalidated()}，它会返回另一种类型
 * {@link RecalledFactWithLifecycle} —— 两者<b>故意不通用</b>，
 * 混用即编译失败（详见 §2.3 三条设计铁律，以及「为什么不做全局配置开关」的三条理由）。
 */
public record RecalledFact(

        /** 事实 uuid（可作删除 / 溯源的操作句柄）。 */
        String factId,

        /** 事实陈述原文（已过 PromptSanitizer，不会为 null）。 */
        String statement,

        /** 生效时刻：优先取业务时间 {@code validAt}，为空时回退 {@code createdAt}。 */
        OffsetDateTime effectiveAt) {
}
