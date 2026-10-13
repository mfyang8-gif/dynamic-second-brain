package com.yangmf.mini_nodepad.enums;

/**
 * Graphiti 写入链的「终态隔离原因」（B1）。
 *
 * <p>审计表 {@code graphiti_write_audit} 的语义是<b>「所有永不（重）投递的终态集合」</b>。
 * 四类终态写入该表后，{@code GraphitiConfirmJob} 与 {@code GraphitiReplayJob} 的
 * {@code NOT EXISTS(审计表)} 会同时挡住两类死循环：
 * <ul>
 *   <li><b>垃圾入图</b>：判否的闲聊被对账任务当成「漏网之鱼」强制重投（前门拦、后门灌）；</li>
 *   <li><b>坏消息互踢</b>：毒消息「保留占位」后对账永远找不到它 → 重投 → 又 400 → 无限互踢。</li>
 * </ul>
 *
 * <p>★ 枚举名即数据库 {@code reason} 列的取值，追加项时必须同步 §7.4 的 DDL 注释。
 */
public enum GraphitiAuditReason {

    /**
     * 实质判定（{@code MemorySignalJudge}）判否：该轮不含任何用户属性 / 事实，永不投递。
     *
     * <p>★ 它同时是 P7 层 4 抽样（「查漏拦」）的<b>唯一数据来源</b> ——
     * 抽样本身就是查「被判为废话的样本里有没有混进真偏好」。
     */
    JUDGE_REJECTED,

    /**
     * 毒消息：载荷错误（400 / 422），重试无意义。
     *
     * <p>⚠️ 判定前必须先用 {@code GraphitiErrorClassifier} 剔除 429 / 403(quota) ——
     * 把限流当毒消息处理，会让高峰期的新偏好<b>成批永久丢失</b>（不是延迟，是再也回不来）。
     */
    POISON,

    /** 可重试异常耗尽重试次数（30s → 2min → 10min 三档全部失败）后的终态。 */
    RETRY_EXHAUSTED,

    /** 对账确认连续 {@code MAX(8)} 轮未命中：窗口罩得住但那条就是永远落不了库。 */
    CONFIRM_TIMEOUT
}
