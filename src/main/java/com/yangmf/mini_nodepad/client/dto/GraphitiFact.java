package com.yangmf.mini_nodepad.client.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * {@code POST /search} 返回单条事实（服务端 {@code FactResult}）。
 *
 * <p>三个必须知道的字段事实（读服务端 {@code dto/retrieve.py} 原文）：
 * <ol>
 *   <li>时间戳序列化格式是 {@code astimezone(UTC).isoformat()}（微秒 + {@code +00:00} offset），
 *       而 {@code /episodes} 那边是 {@code ...Z} —— 两者<strong>都只能</strong>用
 *       {@link OffsetDateTime} 承接。全局 {@code JacksonObjectMapper} 把日期钉成
 *       {@code yyyy-MM-dd'T'HH:mm:ss}（无 offset），拿它解析必然抛异常（陷阱 6），
 *       这也是 client 必须自建 ObjectMapper 的直接原因。</li>
 *   <li>{@code created_at} 非空必填，其余三个时间字段可空 → 全用 {@code OffsetDateTime}（可空引用）。</li>
 *   <li>返回体里<b>没有 group_id</b>（陷阱 9）—— 故本 record 不设该字段，避免误导调用方
 *       以为「能自证归属」；隔离责任 100% 落在请求的 {@code group_ids} 上。</li>
 * </ol>
 */
public record GraphitiFact(

        String uuid,

        String name,

        /** 事实陈述文本（注入 Prompt 前必须过 PromptSanitizer）。 */
        String fact,

        /** 事实开始为真的业务时刻，可空。 */
        OffsetDateTime validAt,

        /** ★ 作废时刻：<b>唯一的有效性判据</b>（§2.6）。 */
        OffsetDateTime invalidAt,

        /** 入库时刻，非空。 */
        OffsetDateTime createdAt,

        /**
         * 系统账本上的失效登记时刻，可空。
         * ⚠️ <b>不参与有效性判定</b> —— 上游 {@code edges.py:271} 把它的描述错写成
         * 「datetime of when the node was invalidated」（陷阱 10），照字段描述写代码会误判。
         * 本类的 {@link #isValid()} 只认 {@code invalidAt}。
         */
        OffsetDateTime expiredAt,

        String sourceNodeUuid,

        String targetNodeUuid,

        /** 产出该事实的 episode uuid 列表；记忆溯源入口（可用它反查原话），默认空列表。 */
        List<String> episodes) {

    public GraphitiFact {
        if (episodes == null) {
            episodes = List.of();
        }
    }

    /** 当前是否有效：只判 {@code invalidAt}（§2.6，三源交叉验证的结论）。 */
    public boolean isValid() {
        return invalidAt == null;
    }

    /**
     * 「该作废却没打作废标记」的观测信号（§2.6 风险缓解）。
     * <p>若未来 Graphiti 新增「只设 {@code expired_at}」的作废路径，只判 {@code invalidAt} 会漏。
     * 缓解手段不是提前把 {@code OR} 塞进判据（会引入静默变严的隐患），而是先让事实暴露出来。
     */
    public boolean hasLifecycleAnomaly() {
        return invalidAt == null && expiredAt != null;
    }
}
