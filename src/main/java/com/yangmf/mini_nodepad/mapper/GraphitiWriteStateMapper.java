package com.yangmf.mini_nodepad.mapper;

import com.yangmf.mini_nodepad.pojo.dto.GraphitiPendingMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Graphiti 写入链的状态机 Mapper（Phase 4 · B1）。
 *
 * <p>这里是「DB CAS 幂等 + 双字段状态机 + 两个职责单一的对账任务」的全部 SQL 实现。
 * 有<b>四条</b>写在这里的约束，任何一条被改坏都会造成静默数据事故：
 *
 * <ol>
 *   <li><b>CAS 占位必须在 POST 之前</b>。若先 POST 再写 {@code dispatched_at}，
 *       一次 ack 丢失导致的 MQ 重投就会在「写字段之前」再次 POST → 重复 episode
 *       （服务端对重复投递<b>零去重能力</b>，铁律 1 是唯一防线）。</li>
 *   <li><b>可重试失败必须回滚占位</b>。不回滚的话，重试消息回来时 CAS 发现
 *       {@code dispatched_at} 非空 → 直接 ack 跳过 → <b>整条 MQ 重试链路当场瘫痪</b>。</li>
 *   <li><b>对账任务只允许扫 {@code dispatched_at IS NOT NULL}</b>。把 {@code IS NULL}
 *       纳入对账视野，会把「判否的闲聊」和「熔断期被跳过的消息」误判成「投递失败」
 *       并强制重投 —— 前者是前门拦后门灌，后者等于熔断失效（暗雷 1 / 暗雷 3）。</li>
 *   <li><b>两条对账 SQL 都必须带 {@code NOT EXISTS(审计表)}</b>。审计表是
 *       「永不（重）投递」的权威标记，缺了它就会发生「毒消息 ↔ 对账任务无限互踢」。</li>
 * </ol>
 *
 * <p>⚠️ 全部 SQL 手写 {@code c.deleted = 0}：本类是注解式 mapper，不走 MyBatis-Plus 的
 * {@code @TableLogic} 自动注入，漏写会把软删消息重新捞出来。
 *
 * <p>⚠️ 三个 JOIN 只为取 {@code book.user_id}（{@code chat_message} 表本身没有 user_id 列）：
 * {@code chat_message.session_id → chat_session.book_id → book.user_id}。
 * 三段都带 {@code deleted = 0}，避免已删会话 / 已删知识库下的消息被误投。
 */
@Mapper
public interface GraphitiWriteStateMapper {

    // ==================== 投递层：CAS 占位 / 回滚 / 落库 ====================

    /**
     * CAS 占位（补丁 1 的核心）：把「投递中」这一软锁拿到手。
     *
     * <p>{@code NOW() - INTERVAL 3 MINUTE} 是<b>自动放开</b>窗口：写入 read timeout 只有 2s，
     * 远小于 3min，因此「占位后 3 分钟内允许接管」不会造成真正的并发双投，
     * 却能保证「消费者崩溃 / 线程被杀」留下的占位不会变成永久死锁。
     *
     * @return 1 = 占位成功（可以投递）；0 = 已被投递过，必须直接 ack 跳过（不去 POST）
     */
    @Update("""
            UPDATE chat_message
               SET graphiti_dispatched_at = NOW()
             WHERE id = #{messageId}
               AND deleted = 0
               AND (graphiti_dispatched_at IS NULL
                    OR graphiti_dispatched_at < NOW() - INTERVAL 3 MINUTE)
            """)
    int casOccupy(@Param("messageId") String messageId);

    /**
     * 回滚占位 —— 一切<b>可重试 / 未预期</b>异常的唯一善后动作。
     *
     * <p>⚠️ 毒消息（400/422）<b>不</b>走这里：它要保留占位（避免坏消息无限重投），
     * 同时写审计表进入终态。
     *
     * <p>带 {@code graphiti_synced_at IS NULL} 是为了永不把「已确认落库」的行改回未投递状态。
     *
     * @return 影响行数（0 表示已被对账任务确认落库，回滚无意义，属正常竞争）
     */
    @Update("""
            UPDATE chat_message
               SET graphiti_dispatched_at = NULL
             WHERE id = #{messageId}
               AND graphiti_synced_at IS NULL
            """)
    int rollbackOccupy(@Param("messageId") String messageId);

    /**
     * 标记「已确认落库」，并顺手回填服务端 episode uuid（零额外请求）。
     *
     * @param episodeUuid 服务端生成的 uuid；取不到（响应里没有）时传 null
     */
    @Update("""
            UPDATE chat_message
               SET graphiti_synced_at = NOW(),
                   graphiti_episode_uuid = #{episodeUuid}
             WHERE id = #{messageId}
               AND graphiti_synced_at IS NULL
            """)
    int markSynced(@Param("messageId") String messageId, @Param("episodeUuid") String episodeUuid);

    /** 对账未命中的一轮计数（配合 {@link #selectConfirmTargets} 使用）。 */
    @Update("""
            UPDATE chat_message
               SET graphiti_confirm_rounds = graphiti_confirm_rounds + 1
             WHERE id = #{messageId}
               AND graphiti_synced_at IS NULL
            """)
    int incrementConfirmRounds(@Param("messageId") String messageId);

    // ==================== 扫描层：窗口取数 ====================

    /**
     * 取「水位之后、尚未投递、未被隔离」的 user 轮次（发现层的输入）。
     *
     * <p>三点设计：
     * <ul>
     *   <li>★ <b>只取 user</b>：AI 回答中的客串假设句会诱导抽取器产生自我强化幻觉；</li>
     *   <li>★ <b>双 NULL 条件</b>（{@code synced} 与 {@code dispatched} 都为 NULL）
     *       是 N=1 逐条投递的前提 —— 少了它，同一轮会在每个扫描周期被反复捞出来；</li>
     *   <li>★ 水位（{@code since}）本身就是幂等：水位推进后不再回头。
     *       调用方在无水位时必须传一个「足够早、且在 MySQL DATETIME 定义域内」的常量
     *       （见 {@code GraphitiScanConsumer#EPOCH_FLOOR} = 1970-01-01），而不是 {@code null}
     *       （走 {@code <if>} 分支会让 SQL 变脆），<b>更不能用 {@code LocalDateTime.MIN}</b>
     *       —— 其年份 −1000000000 超出 DATETIME 的 1000~9999 范围，绑定即抛 error 1525，
     *       表现是「每个新会话首次扫描必进 DLQ」。</li>
     * </ul>
     *
     * @param limit 单轮上限，避免一次扫描把整段历史全捞出来压垮 LLM 判定
     */
    @Select("""
            SELECT c.id          AS messageId,
                   c.session_id  AS sessionId,
                   b.user_id     AS userId,
                   c.content     AS content,
                   c.created_at  AS createdAt
              FROM chat_message c
              JOIN chat_session s ON s.id = c.session_id
              JOIN book b        ON b.id = s.book_id
             WHERE c.session_id = #{sessionId}
               AND c.deleted = 0
               AND s.deleted = 0
               AND b.deleted = 0
               AND c.role = 'user'
               AND c.graphiti_synced_at IS NULL
               AND c.graphiti_dispatched_at IS NULL
               AND c.created_at > #{since}
             ORDER BY c.created_at ASC
             LIMIT #{limit}
            """)
    List<GraphitiPendingMessage> selectScanWindow(@Param("sessionId") String sessionId,
                                                  @Param("since") LocalDateTime since,
                                                  @Param("limit") int limit);

    // ==================== 对账（Confirm）：只扫「已投递但未确认」 ====================

    /**
     * 对账目标集。
     *
     * <p>★ 三个 WHERE 各有出处，缺一不可：
     * <ul>
     *   <li>{@code dispatched_at IS NOT NULL} —— 只看「已投递」。判否消息的
     *       {@code dispatched_at} 恒为 NULL，因此<b>连进入对账视野的可能性都没有</b>
     *       （暗雷 1 的结构性修复）；</li>
     *   <li>{@code < NOW() - 3 MINUTE} —— 覆盖 202 → 落库 的确认窗（实测落库 15~45s）；</li>
     *   <li>{@code NOT EXISTS(审计表)} —— 第二道防线：毒消息 / 重试耗尽 / 已超限的消息不参与对账。</li>
     * </ul>
     *
     * <p>★ <b>刻意把 {@code content} 与 {@code created_at} 一并取回</b>：
     * 对账在「宽限期后仍未命中」时要执行重投，而重投载荷必须是<b>完整原文</b>+
     * <b>原始业务时刻</b>（{@code timestamp} 一旦被换成「重投时刻」，双时间戳裁决就失真）。
     * 若等到重投时再逐条点查，就是一个典型的 N+1。
     */
    @Select("""
            SELECT c.id                       AS messageId,
                   c.session_id               AS sessionId,
                   b.user_id                  AS userId,
                   c.graphiti_confirm_rounds  AS confirmRounds,
                   c.content                  AS content,
                   c.created_at               AS createdAt
              FROM chat_message c
              JOIN chat_session s ON s.id = c.session_id
              JOIN book b        ON b.id = s.book_id
             WHERE c.deleted = 0
               AND s.deleted = 0
               AND b.deleted = 0
               AND c.graphiti_synced_at IS NULL
               AND c.graphiti_dispatched_at IS NOT NULL
               AND c.graphiti_dispatched_at < NOW() - INTERVAL 3 MINUTE
               AND NOT EXISTS (SELECT 1 FROM graphiti_write_audit a WHERE a.message_id = c.id)
             ORDER BY c.graphiti_dispatched_at ASC
             LIMIT #{limit}
            """)
    List<GraphitiPendingMessage> selectConfirmTargets(@Param("limit") int limit);

    /**
     * 最老目标的投递时刻（{@code /episodes} 窗口计算的起点）。
     *
     * <p>★ 量纲必须是 {@code dispatched_at} 而不是 {@code chat_message.created_at}：
     * 历史补投（宕机修复后）与长重试链（>10min 才落库）的消息业务时间很旧，
     * 用 {@code created_at} 会让窗口起算点大幅前移、{@code last_n} 退化成保底值，
     * 恰好罩不住目标（暗雷 3 洞 1）。
     *
     * @return 无目标时返回 null
     */
    @Select("""
            SELECT MIN(c.graphiti_dispatched_at)
              FROM chat_message c
             WHERE c.deleted = 0
               AND c.graphiti_synced_at IS NULL
               AND c.graphiti_dispatched_at IS NOT NULL
               AND c.graphiti_dispatched_at < NOW() - INTERVAL 3 MINUTE
               AND NOT EXISTS (SELECT 1 FROM graphiti_write_audit a WHERE a.message_id = c.id)
            """)
    LocalDateTime selectOldestConfirmDispatchedAt();

    /**
     * 该时间窗内【已投递】的消息数 = 会排在目标之上的 episode 数上界。
     *
     * <p>因为 {@code /episodes} 按<b>落库时刻</b>倒序（已实测），而落库顺序 ≈ 投递顺序，
     * 「比最老目标更新的 episode 数」就等于「在最老目标投递之后投出去的消息数」。
     */
    @Select("""
            SELECT COUNT(1)
              FROM chat_message
             WHERE deleted = 0
               AND graphiti_dispatched_at IS NOT NULL
               AND graphiti_dispatched_at >= #{since}
               AND graphiti_dispatched_at <= NOW()
            """)
    long countDispatchedSince(@Param("since") LocalDateTime since);

    // ==================== 补投（Replay）：只扫「该投但没投出去」 ====================

    /**
     * 补投目标集 —— 铁律 4「只延迟、不丢弃」的唯一执行者。
     *
     * <p>它是两类数据的<b>唯一补投责任人</b>：
     * ① 背压熔断期被跳过的消息；② scan 崩溃漏投的消息。
     *
     * <p>★ 它<b>不会</b>把判否的垃圾捞回来：判否的已进审计表，被 {@code NOT EXISTS} 排除。
     * ★ {@code created_at < NOW() - 5 MINUTE} 是排除「正在被扫描层处理的正常消息」——
     * 少了它，正在编辑中的会话会被补投与扫描同时投递。
     */
    @Select("""
            SELECT c.id         AS messageId,
                   c.session_id AS sessionId,
                   b.user_id    AS userId,
                   c.content    AS content,
                   c.created_at AS createdAt
              FROM chat_message c
              JOIN chat_session s ON s.id = c.session_id
              JOIN book b        ON b.id = s.book_id
             WHERE c.deleted = 0
               AND s.deleted = 0
               AND b.deleted = 0
               AND c.role = 'user'
               AND c.graphiti_synced_at IS NULL
               AND c.graphiti_dispatched_at IS NULL
               AND NOT EXISTS (SELECT 1 FROM graphiti_write_audit a WHERE a.message_id = c.id)
               AND c.created_at < NOW() - INTERVAL 5 MINUTE
             ORDER BY c.created_at ASC
             LIMIT #{limit}
            """)
    List<GraphitiPendingMessage> selectReplayTargets(@Param("limit") int limit);

    // ==================== 观测（不参与任何熔断触发） ====================

    /**
     * 「未确认数」 —— 只作告警与看板。
     *
     * <p>★★ 严禁用它做熔断触发：可重试失败会回滚 {@code dispatched_at}，
     * Graphiti 全挂时每一条都被回滚 → 未确认数趋近 0 → <b>熔断器会报「系统无比健康」并继续猛灌</b>。
     * 熔断信号只能取「消息在哪个物理容器里」（MQ 积压），因为那是物理事实，
     * 而数据库字段是会被我们自己改写的软状态（暗雷 2）。
     */
    @Select("""
            SELECT COUNT(1)
              FROM chat_message
             WHERE deleted = 0
               AND graphiti_synced_at IS NULL
               AND graphiti_dispatched_at IS NOT NULL
               AND graphiti_dispatched_at < NOW() - INTERVAL #{minutes} MINUTE
            """)
    long countUnconfirmedOlderThan(@Param("minutes") int minutes);
}
