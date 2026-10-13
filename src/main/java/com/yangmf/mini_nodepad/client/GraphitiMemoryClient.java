package com.yangmf.mini_nodepad.client;

import com.yangmf.mini_nodepad.client.dto.EpisodeRef;
import com.yangmf.mini_nodepad.client.dto.GraphitiMessage;
import com.yangmf.mini_nodepad.client.dto.GraphitiWriteAck;
import com.yangmf.mini_nodepad.client.dto.RecalledFact;
import com.yangmf.mini_nodepad.client.dto.RecalledFactWithLifecycle;

import java.util.List;

/**
 * Graphiti 记忆服务客户端 —— 契约层唯一出口（B0）。
 *
 * <p>三条设计铁律（§2.3）：
 * <ol>
 *   <li><b>{@link #recallActive} 是唯一召回出口</b>，过滤发生在其内部而不是靠调用点自觉 ——
 *       因此 {@link RecalledFact} 在<b>类型上</b>不可能携带已作废事实。</li>
 *   <li><b>想看已作废事实，必须显式调用另一个方法名</b>（{@link #recallIncludingInvalidated}）。
 *       「要打错字才会犯错」就是防呆本身。</li>
 *   <li><b>旁路返回独立类型</b>（{@link RecalledFactWithLifecycle}），
 *       而不是给 {@link RecalledFact} 加可空字段 —— 否则「类型系统即防呆」会被撤销：
 *       拿到结果的代码无法区分它是过滤过的还是没过滤的。</li>
 * </ol>
 *
 * <p>⚠️ 本接口<b>没有</b> {@code include-invalidated} 之类的全局开关，这是刻意的（§2.3 三条理由）：
 * 本 Phase 唯一的存在理由就是「改口裁决」，而一个能被误开、忘关的全局布尔，
 * 等于给「关掉核心能力」留后门 —— 且失效是静默的（无异常、无日志、无告警）。
 */
public interface GraphitiMemoryClient {

    /**
     * 召回<b>当前有效</b>的事实。内部依次完成：
     * ① {@code groupIds} 非空断言 → ② 宽拉取（{@code limit × fetchFactor}）
     * → ③ 剔除 {@code invalidAt != null} → ④ 消毒（{@code PromptSanitizer}）
     * → ⑤ 截断回 {@code limit}。
     *
     * <p>★ 铁律 5：③ 必须在 ⑤ 之前。反过来的话，用户改口频繁时
     * 「匹配度最高的 N 条恰好全作废」会让有效事实（第 N+1 条）根本没被拉回来，
     * 结果是<b>召回为空</b>而不是精度下降（补丁 2 / 陷阱 20）。
     *
     * @param userId 用户主键，内部经 {@link GroupIdResolver} 转为 {@code user_{userId}}
     * @param query  检索语句
     * @param limit  调用方期望的<b>最终</b>条数；放大只发生在实现内部，调用方无需知道
     * @throws IllegalArgumentException userId 非法 / query 为空 / limit 非正
     * @throws GraphitiClientException  调用失败（超时、非 2xx）
     */
    List<RecalledFact> recallActive(Long userId, String query, int limit);

    /**
     * 语义化旁路：召回<b>含已作废</b>的事实。
     *
     * <p>仅供三类消费者使用：排障、「我的记忆」的变更历史（走独立只读接口）、
     * 双时间戳验收测试。常规对话链路<b>不得</b>调用本方法。
     *
     * @param limit 期望条数（同样走宽拉取，但<b>不过滤</b>作废事实）
     */
    List<RecalledFactWithLifecycle> recallIncludingInvalidated(Long userId, String query, int limit);

    /**
     * 写入一条消息（N=1，§3.9）。
     *
     * <p>内部已强制包装 {@code messages[]}（陷阱 1）、
     * 断言 {@code uuid == null}（陷阱 31）、并生成 {@code group_id}。
     *
     * <p>⚠️ 返回 {@code accepted=true} <b>不代表落库成功</b>：
     * 服务端是异步的，202 只代表入队，落库确认必须靠异步对账（§3.6）。
     */
    GraphitiWriteAck write(Long userId, GraphitiMessage message);

    /**
     * 列出分区内最近的 episode（时间倒序）。
     *
     * <p>用途：落库确认（比对 {@code name} 锚点）与记忆溯源（{@code fact.episodes[]} 反查原话）。
     *
     * <p>⚠️ {@code last_n} 必填且<b>无法按条件过滤</b>（未知参数被静默忽略），
     * 所以调用方必须自己算一个罩得住目标的窗口（§3.6.1，暗雷 3）；
     * 且<b>排序键是 {@code created_at}（落库时刻）</b>，不是 {@code valid_at}。
     *
     * @param lastN 窗口条数，必须为正
     */
    List<EpisodeRef> listEpisodes(Long userId, int lastN);

    /**
     * 删除一条边（遗忘权 / 纠错入口）。
     *
     * @param edgeUuid 边 uuid
     */
    void deleteEdge(String edgeUuid);

    /**
     * 健康探测。失败时返回可读的失败描述而<b>不抛异常</b>（健康检查自身不该成为故障源）。
     *
     * @return 形如 {@code "HTTP 200"} 或 {@code "DOWN: ResourceAccessException - ..."}
     */
    String healthcheck();
}
