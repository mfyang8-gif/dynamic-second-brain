package com.yangmf.mini_nodepad.job;

import com.yangmf.mini_nodepad.client.GraphitiMemoryClient;
import com.yangmf.mini_nodepad.client.GroupIdResolver;
import com.yangmf.mini_nodepad.client.dto.EpisodeRef;
import com.yangmf.mini_nodepad.client.dto.GraphitiMessage;
import com.yangmf.mini_nodepad.enums.GraphitiAuditReason;
import com.yangmf.mini_nodepad.mapper.GraphitiWriteAuditMapper;
import com.yangmf.mini_nodepad.mapper.GraphitiWriteStateMapper;
import com.yangmf.mini_nodepad.mq.GraphitiMqConfig;
import com.yangmf.mini_nodepad.mq.GraphitiWriteMessage;
import com.yangmf.mini_nodepad.pojo.dto.GraphitiPendingMessage;
import com.yangmf.mini_nodepad.properties.GraphitiProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Graphiti 落库对账任务（B1，§3.6 S2 定稿）。
 *
 * <p>它只管一件事：<b>「已投递、但没确认落库」的消息</b>。
 * 为什么需要它：{@code POST /messages} 返回的 202 只代表「进了服务端的 asyncio.Queue」，
 * 首条 episode 实测 15~45s 才进 Neo4j，而服务端后台 job 失败时 HTTP 早已返回成功 ——
 * 不确认就等于「重试机制形同虚设」。
 *
 * <p>★ <b>为什么把它与 Replay 拆成两个任务</b>（暗雷 1 的结构性修复）：
 * {@code dispatched_at IS NULL} 有<b>三种</b>互不相同的语义
 * （① 尚未投递 ② 被判定器判否 ③ 毒消息/重试耗尽），
 * 把它们塞进同一条 WHERE 就会「前门拦、后门灌」——
 * 判否的闲聊会被当成漏网之鱼强制重投进图谱。
 * 拆开后，本任务<b>只允许看 {@code IS NOT NULL}</b>，连犯错的入口都没有。
 *
 * <p>★ <b>窗口量纲</b>（暗雷 3）：{@code last_n} 必须覆盖「比最老目标更新的所有 episode」，
 * 也就是「在 {@code [最老目标的 dispatched_at, now]} 这个时间窗内投递出去的消息数」——
 * <b>不是</b> {@code chat_message.created_at}（历史补投与长重试链会让它算得极小），
 * <b>也不是</b>「未确认数」（一条卡住 2 小时、其间正常落了 200 条时它 ≈ 1）。
 * 两个错法都会导致目标被挤出窗口 → 判「投递失败」→ 无限重投。
 *
 * <p>⚠️ <b>一轮 Graphiti 故障绝不能烧掉确认轮次</b>：{@code listEpisodes} 抛异常时必须
 * <b>整组跳过</b>（不计数、不重投、不隔离）。否则 Graphiti 宕机 40 分钟
 * （{@code MAX(8) 轮 × 5min}）就会把全部待确认消息隔离成终态 ——
 * 那正是「容错机制自己把库清了」。
 */
@Slf4j
@Component
public class GraphitiConfirmJob {

    private final GraphitiWriteStateMapper stateMapper;
    private final GraphitiWriteAuditMapper auditMapper;
    private final GraphitiMemoryClient client;
    private final RabbitTemplate rabbitTemplate;
    private final GraphitiProperties properties;

    public GraphitiConfirmJob(GraphitiWriteStateMapper stateMapper,
                              GraphitiWriteAuditMapper auditMapper,
                              GraphitiMemoryClient client,
                              RabbitTemplate rabbitTemplate,
                              GraphitiProperties properties) {
        this.stateMapper = stateMapper;
        this.auditMapper = auditMapper;
        this.client = client;
        this.rabbitTemplate = rabbitTemplate;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${graphiti.confirm.interval-ms:300000}",
            initialDelayString = "${graphiti.confirm.interval-ms:300000}")
    public void confirm() {
        GraphitiProperties.Confirm config = properties.getConfirm();
        if (!config.isEnabled() || !properties.getWrite().isEnabled()) {
            return;
        }

        List<GraphitiPendingMessage> targets = stateMapper.selectConfirmTargets(Math.max(1, config.getBatchLimit()));
        if (targets.isEmpty()) {
            return;
        }

        int lastN = dynamicLastN(config);
        Map<String, List<GraphitiPendingMessage>> byUser = groupByUser(targets);

        int confirmed = 0;
        int recounted = 0;
        int requeued = 0;
        int isolated = 0;
        int skippedGroups = 0;

        for (Map.Entry<String, List<GraphitiPendingMessage>> entry : byUser.entrySet()) {
            Long userId = GroupIdResolver.parseUserId(entry.getKey());
            if (userId == null) {
                log.error("对账目标 userId 非法，本轮跳过该组（需人工核查 book.user_id） | rawUserId={}, 条数={}",
                        entry.getKey(), entry.getValue().size());
                skippedGroups++;
                continue;
            }

            List<EpisodeRef> episodes;
            try {
                episodes = client.listEpisodes(userId, lastN);
            } catch (Exception e) {
                // ★ 关键：Graphiti 不可用时整组跳过 —— 不计数、不重投、不隔离。
                // 把「查不到」当成「投递失败」会在大面积故障时把全部数据隔离成终态。
                log.warn("拉取 episode 窗口失败，本轮跳过该组（不计入确认轮次） | userId={}, lastN={}",
                        entry.getKey(), lastN, e);
                skippedGroups++;
                continue;
            }

            Map<String, String> anchorToUuid = indexByAnchor(episodes);

            for (GraphitiPendingMessage target : entry.getValue()) {
                String anchor = GraphitiMessage.CONFIRM_ANCHOR_PREFIX + target.getMessageId();
                if (anchorToUuid.containsKey(anchor)) {
                    // ★ 命中即顺手回填服务端 uuid（同一个响应里就有，零额外请求）
                    stateMapper.markSynced(target.getMessageId(), anchorToUuid.get(anchor));
                    confirmed++;
                    continue;
                }

                int rounds = (target.getConfirmRounds() == null ? 0 : target.getConfirmRounds()) + 1;
                stateMapper.incrementConfirmRounds(target.getMessageId());

                if (rounds > config.getMaxRounds()) {
                    auditMapper.insertIgnore(entry.getKey(), target.getMessageId(),
                            GraphitiAuditReason.CONFIRM_TIMEOUT.name(), snapshot(target.getContent()),
                            "对账连续 " + rounds + " 轮未命中（last_n=" + lastN + "），已隔离防无限重投");
                    isolated++;
                    log.error("★ 对账超限，消息隔离为终态（人工删审计行即可恢复重新投递） | messageId={}, rounds={}, lastN={}",
                            target.getMessageId(), rounds, lastN);
                    continue;
                }

                if (rounds <= config.getReplayAfterRounds()) {
                    recounted++;
                    log.debug("对账未命中但在宽限期内（落库 15~45s，别和延迟赛跑） | messageId={}, rounds={}",
                            target.getMessageId(), rounds);
                    continue;
                }

                if (requeue(target)) {
                    requeued++;
                }
            }
        }

        long backlog = stateMapper.countUnconfirmedOlderThan(15);
        log.info("Graphiti 对账完成 | 目标={}, 确认={}, 宽限计数={}, 重投={}, 隔离={}, 跳过组={}, "
                        + "last_n={}, 未确认积压(>15min)={}",
                targets.size(), confirmed, recounted, requeued, isolated, skippedGroups, lastN, backlog);
    }

    /**
     * 动态 {@code last_n}（补丁 3 定稿公式）。
     *
     * <pre>
     * oldestTarget = MIN(dispatched_at)  ← 只看「已投递、未确认、未隔离」
     * X            = COUNT(在该时刻之后投递出去的消息数)
     * last_n       = clamp(X + 10, 保底 50, 封顶 500)
     * </pre>
     */
    private int dynamicLastN(GraphitiProperties.Confirm config) {
        LocalDateTime oldest = stateMapper.selectOldestConfirmDispatchedAt();
        long x = oldest == null ? 0L : stateMapper.countDispatchedSince(oldest);
        long calc = x + 10;
        int min = Math.max(1, config.getMinLastN());
        int max = Math.max(min, config.getMaxLastN());
        if (calc > max) {
            log.warn("对账窗口计算值 {} 超过封顶 {}，可能存在异常流量或窗口设置问题 | oldestTarget={}",
                    calc, max, oldest);
        }
        return (int) Math.min(Math.max(calc, min), max);
    }

    /**
     * 重投：先把占位回滚为 NULL，再投回唯一写入口。
     *
     * <p>★ 顺序不能反：先投后回滚会让重投的消息在 CAS 处被自己挡住
     * （{@code dispatched_at} 仍非空 → 影响 0 行 → 直接 ack 跳过）。
     *
     * <p>★ 载荷必须携带<b>完整原文</b>与<b>原始业务时刻</b>：
     * {@code GraphitiWriteMessage} 的紧凑构造器会拒绝空 content，
     * 从类型层面挡住「用占位正文重投」这种写法；{@code createdAtIso} 若被换成
     * 「重投时刻」，双时间戳裁决会失真，因此使用查询一并取回的 {@code createdAt}。
     */
    private boolean requeue(GraphitiPendingMessage target) {
        try {
            stateMapper.rollbackOccupy(target.getMessageId());
            GraphitiWriteMessage out = GraphitiWriteMessage.normal(
                    target.getMessageId(),
                    target.getSessionId(),
                    target.getUserId(),
                    target.getContent(),
                    target.getCreatedAt() == null ? LocalDateTime.now().toString() : target.getCreatedAt().toString(),
                    "对账重投（未在 episode 窗口内找到确认锚点）");
            rabbitTemplate.convertAndSend(GraphitiMqConfig.EXCHANGE_WRITE, GraphitiMqConfig.ROUTING_WRITE, out,
                    msg -> {
                        msg.getMessageProperties().setPriority(out.priority());
                        return msg;
                    });
            log.warn("对账重投已发出 | messageId={}, rounds={}, lastN 已按投递时间窗动态放大",
                    target.getMessageId(), target.getConfirmRounds());
            return true;
        } catch (Exception e) {
            // 重投失败不写审计表（它不是终态）：占位已回滚为 NULL，
            // → GraphitiReplayJob 会在下一轮把它捞起来补投（延迟 ≠ 丢弃）。
            log.warn("对账重投失败（不写审计表，交给 ReplayJob 兜底） | messageId={}", target.getMessageId(), e);
            return false;
        }
    }

    /**
     * 把 episode 列表按 {@code name} 锚点索引成 {@code anchor -> uuid}。
     *
     * <p>用 {@code Map} 而不是 {@code Set}：命中时顺便就能拿到 uuid 回填，
     * 不需要再遍历一次。
     */
    private Map<String, String> indexByAnchor(List<EpisodeRef> episodes) {
        Map<String, String> index = new HashMap<>();
        if (episodes == null || episodes.isEmpty()) {
            return index;
        }
        for (EpisodeRef episode : episodes) {
            if (episode != null && episode.name() != null && !episode.name().isBlank()) {
                index.put(episode.name(), episode.uuid());
            }
        }
        return index;
    }

    private Map<String, List<GraphitiPendingMessage>> groupByUser(List<GraphitiPendingMessage> targets) {
        Map<String, List<GraphitiPendingMessage>> byUser = new HashMap<>();
        for (GraphitiPendingMessage target : targets) {
            byUser.computeIfAbsent(target.getUserId() == null ? "" : target.getUserId(),
                    key -> new ArrayList<>()).add(target);
        }
        return byUser;
    }

    private String snapshot(String content) {
        if (content == null) {
            return null;
        }
        String trimmed = content.strip();
        return trimmed.length() <= 512 ? trimmed : trimmed.substring(0, 512) + "…";
    }

    /** 供诊断使用：当前待确认集合是否为空（不触发任何写操作）。 */
    Set<String> pendingUserIds() {
        Set<String> ids = new HashSet<>();
        for (GraphitiPendingMessage target : stateMapper.selectConfirmTargets(50)) {
            ids.add(target.getUserId());
        }
        return ids;
    }
}
