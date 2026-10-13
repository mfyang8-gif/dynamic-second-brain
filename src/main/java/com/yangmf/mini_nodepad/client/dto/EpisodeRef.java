package com.yangmf.mini_nodepad.client.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * {@code GET /episodes/{group_id}?last_n=N} 返回的元素。
 *
 * <p>★ 该接口返回的是 <b>EpisodicNode 的原始序列化</b>，不是 DTO ——
 * 11 个字段：{@code content / created_at / entity_edges / episode_metadata(恒 None) /
 * group_id / labels / name / source / source_description / uuid / valid_at}。
 * 本 record 只承接我们实际需要的那部分（Jackson 已关闭 unknown 属性报错）。
 *
 * <p>三个必须记住的接口特性：
 * <ul>
 *   <li><b>{@code last_n} 必填</b>，无默认值；</li>
 *   <li><b>纯时间倒序</b>，排序键是 {@code created_at}（<b>落库时刻</b>，不是
 *       {@code valid_at}）—— 已实测：先投「90 天前」、后投「今天」，返回顺序仍按入库先后；</li>
 *   <li><b>无 offset / 无游标</b>，且未知查询参数被静默忽略（加 {@code &query=React}
 *       返回逐字节一致）→ 实现上不可能做「按条件过滤」，只能拉一个足够大的窗口再本地比对。</li>
 * </ul>
 *
 * <p>⚠️ 正因为「排序键 = 落库时刻」+「无游标」，对账任务的 {@code last_n} <b>必须动态计算</b>：
 * 按「最老未确认目标的投递时刻 → 现在」这段时间窗内的已投递条数来定窗口大小（§3.6.1），
 * 否则一条卡了很久的消息会被新数据挤出窗口，导致<b>无限重投</b>（暗雷 3）。
 */
public record EpisodeRef(

        String uuid,

        /** ★ 确认锚点（约定 {@code "cm_" + chatMessageId}），对账任务按它比对。 */
        String name,

        /** 来源说明（写入时我们填的「人话式来源说明」）。 */
        String sourceDescription,

        /** 落库时刻：{@code /episodes} 的排序键。格式 {@code ...Z}。 */
        OffsetDateTime createdAt,

        /** 业务生效时刻，可空。 */
        OffsetDateTime validAt,

        String groupId,

        /** episode 原文（可能较长）。仅在「记忆溯源」场景需要，对账时用不上。 */
        String content,

        List<String> entityEdges) {

    public EpisodeRef {
        if (entityEdges == null) {
            entityEdges = List.of();
        }
    }
}
