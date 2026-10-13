package com.yangmf.mini_nodepad;

import com.yangmf.mini_nodepad.client.GraphitiMemoryClient;
import com.yangmf.mini_nodepad.client.dto.EpisodeRef;
import com.yangmf.mini_nodepad.client.dto.GraphitiMessage;
import com.yangmf.mini_nodepad.enums.GraphitiAuditReason;
import com.yangmf.mini_nodepad.mapper.GraphitiWriteAuditMapper;
import com.yangmf.mini_nodepad.mapper.GraphitiWriteStateMapper;
import com.yangmf.mini_nodepad.mq.GraphitiScanConsumer;
import com.yangmf.mini_nodepad.mq.GraphitiScanMessage;
import com.yangmf.mini_nodepad.job.GraphitiConfirmJob;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Assumptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 4 · B1 写入链 <b>端到端实机诊断</b>（附录 D 核心项的自动化载体）。
 *
 * <p>它跑的是<b>真链路</b>：真 MySQL（CAS 占位 / 双字段状态机）、真 RabbitMQ（两级队列 + 监听器）、
 * 真 Graphiti（{@code POST /messages} + {@code GET /episodes} 确认）。唯一被替换掉的是
 * 「等 5 分钟 delay 队列过期」这一步 —— 直接把 {@code GraphitiScanMessage} 交给扫描消费者，
 * 其余全按生产路径走。
 *
 * <p>★ <b>为什么用一次性夹具而不是拿真实会话跑</b>：真实会话会把 episode 写进真实用户的图谱里，
 * 而这是一次「为了验证机制」的写入 —— 验证完还要清理，只会平添污染。所以本测试自建
 * {@code book/chat_session/chat_message} 三行（探针用户 {@code 999999}），
 * 结束后连图谱 group 一起删干净。
 *
 * <p><b>它验证的四件事</b>（每一条都对应设计文档里一个「错一处就静默丢数据」的点）：
 * <ol>
 *   <li><b>发现层</b>：水位取窗 → 高危词表命中（{@code 过敏}）→ 走规则快通道直接放行 → 投 {@code write.queue}
 *       （命中词表即跳过 LLM 判定，因此本测试<b>不依赖也不消耗</b> LLM 判定调用）；</li>
 *   <li><b>投递层</b>：{@code casOccupy} 把 {@code dispatched_at} 占上 → POST 拿到 202 → ack；</li>
 *   <li><b>对账层</b>：{@code /episodes} 里按 {@code name = "cm_"+id} 命中 → {@code markSynced} +
 *       回填 {@code graphiti_episode_uuid}；</li>
 *   <li><b>终态语义</b>：全程审计表<b>不该</b>出现这条消息（它不是判否 / 毒消息 / 耗尽 / 超限）。</li>
 * </ol>
 *
 * <p>⚠️ 依赖外部服务（MySQL / RabbitMQ / Graphiti）。任一不可达时用 {@code Assumptions} 跳过，
 * 而不是把环境问题伪装成 B1 的失败。
 *
 * <p>用法：{@code mvn -o test -Dtest=GraphitiWriteChainDiagnosticTest}
 */
@SpringBootTest(properties = {
        "graphiti.write.enabled=true",
        "graphiti.write.judge-enabled=false",
        "graphiti.confirm.enabled=true",
        "graphiti.replay.enabled=false",
        "graphiti.backpressure.enabled=false"
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("B1 写入链端到端诊断")
class GraphitiWriteChainDiagnosticTest {

    private static final String PROBE_USER = "999999";
    private static final String GROUP = "user_" + PROBE_USER;
    /** Graphiti 服务端根地址（与 application.yaml 的 graphiti.base-url 同值）。 */
    private static final String GRAPHITI_BASE = "http://192.168.88.130:8088";
    /** ⚠️ book.id 是 varchar(32) —— 夹具 ID 超长会 1406 Data too long。 */
    private static final String BOOK_ID = "b1probe0000000000000000000000001";
    private static final String SESSION_ID = "b1probe0000000000000000000000002";
    private static final String MESSAGE_ID = "b1probe0000000000000000000000003";
    /** 附录 D #12 用的「必被判否」候选消息（写入审计终态后永不被补投）。 */
    private static final String BLOCKED_MESSAGE_ID = "b1probe0000000000000000000000004";
    /** 含高危词「过敏」→ 命中词表 → 规则快通道放行，不调 LLM 判定器。 */
    private static final String CONTENT = "我对花生过敏，绝对不能吃花生，请务必记住。";

    @Autowired
    private DataSource dataSource;
    @Autowired
    private GraphitiScanConsumer scanConsumer;
    @Autowired
    private GraphitiConfirmJob confirmJob;
    @Autowired
    private GraphitiWriteAuditMapper auditMapper;
    @Autowired
    private GraphitiWriteStateMapper stateMapper;
    @Autowired
    private GraphitiMemoryClient client;
    @Autowired
    private StringRedisTemplate redisTemplate;

    @BeforeAll
    static void banner() {
        System.out.println("""
                
                ==========================================================================================
                B1 写入链端到端诊断：真 MySQL + 真 RabbitMQ + 真 Graphiti（探针用户 999999，跑完即清理）
                ==========================================================================================""");
    }

    @BeforeAll
    static void checkGraphitiReachable() {
        // 放在 @BeforeAll 里跳过：环境不可达是环境问题，不是 B1 的失败
        try {
            HttpResponse<String> res = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(GRAPHITI_BASE + "/healthcheck"))
                            .timeout(Duration.ofSeconds(5)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            Assumptions.assumeTrue(res.statusCode() == 200, "Graphiti 不可达，跳过实机诊断");
        } catch (Exception e) {
            Assumptions.abort("Graphiti 不可达（" + e.getMessage() + "），跳过实机诊断");
        }
    }

    // ==================== ① 夹具 ====================

    @Test
    @Order(1)
    @DisplayName("① 建立一次性夹具（三段 JOIN 能取到 userId，四字段初始为 NULL）")
    void fixture() throws Exception {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate("DELETE FROM chat_message WHERE id = '" + MESSAGE_ID + "'");
            st.executeUpdate("DELETE FROM chat_session WHERE id = '" + SESSION_ID + "'");
            st.executeUpdate("DELETE FROM book WHERE id = '" + BOOK_ID + "'");
            st.executeUpdate("DELETE FROM graphiti_write_audit WHERE message_id = '" + MESSAGE_ID + "'");
            st.executeUpdate("INSERT INTO book (id, user_id, name) VALUES ('" + BOOK_ID + "', '" + PROBE_USER + "', '__B1_PROBE__')");
            st.executeUpdate("INSERT INTO chat_session (id, book_id) VALUES ('" + SESSION_ID + "', '" + BOOK_ID + "')");
            st.executeUpdate("INSERT INTO chat_message (id, session_id, role, content) VALUES ('"
                    + MESSAGE_ID + "', '" + SESSION_ID + "', 'user', '" + CONTENT + "')");
        }
        wipeProbeGraph();
        redisTemplate.delete("graphiti:scan:watermark:" + SESSION_ID);
        redisTemplate.delete("graphiti:scan:armed:" + SESSION_ID);

        assertEquals(1, countRows("SELECT COUNT(1) FROM chat_message c "
                        + "JOIN chat_session s ON s.id = c.session_id JOIN book b ON b.id = s.book_id "
                        + "WHERE c.id = '" + MESSAGE_ID + "' AND b.user_id = '" + PROBE_USER + "'"),
                "夹具的归属链（chat_message → chat_session → book.user_id）必须通");
        assertEquals(0, auditRows(), "起点：审计表必须干净");
        assertNullOfDispatched("起点：dispatched_at 必须为 NULL");
    }

    // ==================== ② 发现层 + ③ 投递层 ====================

    @Test
    @Order(2)
    @DisplayName("②③ 扫描 → 规则快通道 → CAS 占位 → POST 202（dispatched_at 被占上）")
    void scanThenDispatch() throws Exception {
        scanConsumer.onScanMessage(new GraphitiScanMessage("b1-probe-" + System.nanoTime(),
                SESSION_ID, PROBE_USER, null));

        // 投递是异步的（listener 容器消费 write.queue）—— 等 dispatched_at 被 CAS 占上
        awaitTrue("CAS 占位（dispatched_at 非空）", Duration.ofSeconds(60),
                () -> !isNullOfDispatched());
        // 记下「占位被拿到的时刻」，供下一步量落库耗时（附录 D #7）
        dispatchedObservedAt = System.currentTimeMillis();

        assertNotNull(dispatchedAt(), "CAS 占位必须写进 dispatched_at（钉死「CAS 前置」这条铁律）");
        assertTrue(isNullOfSynced(), "此刻 synced_at 仍应为 NULL —— 202 不等于落库（这正是对账存在的理由）");
        assertEquals(0, auditRows(), "正常路径不该产生任何终态隔离行");

        // ★ 幂等回归（DB CAS 的直接证据）：再扫同一窗口，不得刷新 dispatched_at。
        // 该断言之所以有牙齿：selectScanWindow 带 `graphiti_dispatched_at IS NULL`，
        // 而 scan 层还会把水位推进到本轮最大 created_at —— 两道门都拦不住才算真的重复投递。
        String firstStamp = dispatchedAt();
        scanConsumer.onScanMessage(new GraphitiScanMessage("b1-probe-again-" + System.nanoTime(),
                SESSION_ID, PROBE_USER, null));
        // onScanMessage 是本线程同步执行（此处直接调用，不经监听器容器）；留 2s 只为兜住
        // 「万一它真的又投了一条，write 消费者会异步回写」这种本该不可能的路径。
        Thread.sleep(2_000);
        assertEquals(firstStamp, dispatchedAt(),
                "重复扫描不得刷新 graphiti_dispatched_at —— 该行已被 CAS 占用，"
                        + "selectScanWindow 的 dispatched_at IS NULL 条件应把它整个排除");
        assertEquals(0, auditRows(), "重复扫描被挡住时不应产生任何审计行");
        assertTrue(isNullOfSynced(), "二次扫描不得伪造落库确认");
    }

    // ==================== ④ 对账层 ====================

    /** 占位被 CAS 拿到的时刻（Java 侧时钟）。用它而不是拿 SQL 里的 DATETIME 相减，
     *  是为了彻底绕开「MySQL 会话时区 vs Graphiti 的 UTC」这类跨时区换算陷阱。 */
    private static volatile long dispatchedObservedAt = 0L;

    @Test
    @Order(3)
    @DisplayName("④ 对账闭环：等真落库 → 满足 3 分钟确认窗 → confirm 命中 → markSynced + 回填 uuid")
    void confirmLoopCloses() throws Exception {
        String anchor = GraphitiMessage.CONFIRM_ANCHOR_PREFIX + MESSAGE_ID;

        // ── 第一段：等服务端真正落库 ──────────────────────────────────────────
        // 这一段本身就是附录 D #7 的实测载体：POST 拿到 202 只代表「进了服务端 asyncio 队列」，
        // episode 能被 /episodes 查到才是落库。中间隔着一次 LLM 抽取 + 一次 embedding。
        // ⚠️ group 为空时 /episodes 返回 404，第一次轮询必然抛异常 —— 那是「还没落库」的正常表现，
        //    绝不能当成失败（这正是当初写出「对账 10 轮未命中」假故障的同类思维）。
        boolean landed = false;
        long deadline = System.currentTimeMillis() + Duration.ofSeconds(180).toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (anchorVisible(anchor)) {
                landed = true;
                break;
            }
            Thread.sleep(10_000);
        }
        assertTrue(landed, "180s 内 /episodes 仍未出现锚点 " + anchor
                + " —— 服务端根本没落库（附录 D #7），对账无从谈起");
        long latencyMs = dispatchedObservedAt == 0 ? -1 : System.currentTimeMillis() - dispatchedObservedAt;
        System.out.printf("%n★ 附录 D #7：CAS 占位 → 锚点可在 /episodes 读到，约 %d ms"
                + "（轮询粒度 10s，故这是上界，非精确值）%n", latencyMs);

        // ── 第二段：把目标送进对账视野 ────────────────────────────────────────
        // ★ 为什么可以不等那 3 分钟：selectConfirmTargets 硬编码
        //   `dispatched_at < NOW() - INTERVAL 3 MINUTE`，它覆盖的是「202 → 落库」的确认窗
        //   （实测 15~45s）。干等 3 分钟验证的是「等」这件事本身，不是对账逻辑。
        //   把投递时刻前挪 5 分钟即可让目标立刻合格 —— 此后 SQL、HTTP、锚点比对、
        //   markSynced、uuid 回填仍然全部走真实路径。
        //   （首轮 150s 的失败正是因为 150s < 180s，targets 恒为空、confirm() 在第 85 行直接返回。）
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate("UPDATE chat_message SET graphiti_dispatched_at = NOW() - INTERVAL 5 MINUTE "
                    + "WHERE id = '" + MESSAGE_ID + "'");
        }

        int rounds = 0;
        while (rounds < 8 && isNullOfSynced()) {
            rounds++;
            confirmJob.confirm();
            if (isNullOfSynced()) {
                Thread.sleep(3_000);
            }
        }

        assertFalse(isNullOfSynced(),
                "对账 " + rounds + " 轮后仍未确认落库 —— 检查：① /episodes 是否已出现 name=" + anchor
                        + "；② 动态 last_n 是否罩住了目标；③ Confirm SQL 是否误带了 NOT EXISTS");

        String uuid = episodeUuid();
        System.out.printf("★ 对账在第 %d 轮命中：synced_at=%s，episode_uuid=%s%n%n", rounds, syncedAt(), uuid);
        assertNotNull(uuid, "命中时必须顺手回填服务端 episode uuid（遗忘权 DELETE /episode/{uuid} 依赖它）");

        // 审计表始终不该有它 —— 它是「永不（重）投递」的终态集合，正常消息不属于这里
        assertEquals(0, auditRows(), "正常落库的消息绝不能出现在审计表（否则 Replay 会永久拒收它）");
    }

    /** 锚点是否已能从 /episodes 读回。group 为空时服务端 404，按「尚未落库」处理而非失败。 */
    private boolean anchorVisible(String anchor) {
        try {
            return client.listEpisodes(Long.valueOf(PROBE_USER), 50).stream()
                    .anyMatch(e -> anchor.equals(e.name()));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 清空探针用户的图谱分区 —— 夹具的前置动作，不做测试就会假阳性。
     *
     * <p>★ 为什么必须在【每次开跑前】清：Graphiti 服务端对重复投递<b>零去重能力</b>
     * （§3.5 铁律 1 的由来），而夹具用的是固定 message id。上一次运行留下的
     * {@code cm_<id>} episode 会让本轮「等服务端落库」这一步<b>第一轮轮询就命中</b> ——
     * 测试照样全绿，但附录 D #7 的落库耗时量出来的是「跑了两遍的间隔」这种毫无意义的数。
     * 这不是推测：本轮就实测到了两条同名 episode（created_at 相隔 6 分钟）。
     *
     * <p>⚠️ 用路径形式 {@code DELETE /group/{groupId}}；查询参数形式
     * {@code /group?group_id=…} 不会报错，但**什么也不会删**。
     * 同理 {@code GET /episodes/{groupId}?last_n=N} 才是正确形式 ——
     * 写成 {@code /episodes?group_id=…} 会得到 404，看起来像「没有数据」。
     */
    private void wipeProbeGraph() {
        try {
            HttpResponse<String> res = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(GRAPHITI_BASE + "/group/" + GROUP))
                            .timeout(Duration.ofSeconds(10)).DELETE().build(),
                    HttpResponse.BodyHandlers.ofString());
            // 200 = 已删除；404 = 本来就没有（首次运行）—— 两者都算「分区是干净的」
            if (res.statusCode() >= 300 && res.statusCode() != 404) {
                throw new IllegalStateException("清空探针图谱失败 HTTP " + res.statusCode() + "：" + res.body());
            }
            System.out.printf("清空探针图谱 %s → HTTP %d%n", GROUP, res.statusCode());
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "无法清空探针图谱（" + e.getMessage() + "）：上一次运行的 episode 会让本轮假阳性，因此中止而非继续", e);
        }
    }

    // ==================== ⑤ 附录 D #8 / #2b：/episodes 实测 ====================

    @Test
    @Order(4)
    @DisplayName("⑤ 附录 D #8/#2b：/episodes 窗口耗时与体积、排序键、name 锚点可读回")
    void episodesProbe() throws Exception {
        List<EpisodeRef> episodes = client.listEpisodes(Long.valueOf(PROBE_USER), 50);
        assertFalse(episodes.isEmpty(), "此时探针 group 里应有至少 1 条 episode");
        boolean anchorFound = episodes.stream()
                .anyMatch(e -> ("cm_" + MESSAGE_ID).equals(e.name()));
        assertTrue(anchorFound, "确认锚点 name=cm_" + MESSAGE_ID + " 必须能从 /episodes 读回");

        // #2b 排序键回归：必须是 created_at 倒序（§3.6.1 的动态 last_n 公式依赖它）。
        // ★ 用 OffsetDateTime 比较而非字符串比较 —— 字符串比较只在「全部同 offset 同格式」
        //   时才等价，一旦服务端换了序列化形式（+00:00 vs Z）就会假阳性/假阴性。
        for (int i = 1; i < episodes.size(); i++) {
            OffsetDateTime prev = episodes.get(i - 1).createdAt();
            OffsetDateTime cur = episodes.get(i).createdAt();
            assertFalse(prev.isBefore(cur),
                    "★ 排序键回归失败：/episodes 第 " + i + " 条(createdAt=" + cur
                            + ") 早于前一条(" + prev + ") → 不再是 created_at 倒序 → 动态 last_n 前提失效");
        }

        // #8 固定大值的代价：last_n=20 vs 200 的耗时与体积
        // ⚠️ 必须用【路径形式】/episodes/{group_id}?last_n=N。写成查询参数
        //    /episodes?group_id=… 服务端返回 404，量出来的「耗时/体积」是 404 响应体的，
        //    数字看起来很正常但完全无意义（本轮实测踩过）。
        HttpClient http = HttpClient.newHttpClient();
        for (int lastN : new int[]{20, 200}) {
            long t0 = System.currentTimeMillis();
            HttpResponse<String> res = http.send(
                    HttpRequest.newBuilder(URI.create(GRAPHITI_BASE + "/episodes/" + GROUP + "?last_n=" + lastN))
                            .timeout(Duration.ofSeconds(30)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            System.out.printf("附录 D #8: GET /episodes/%s?last_n=%-4d status=%d 耗时=%4dms 体积=%d 字节%n",
                    GROUP, lastN, res.statusCode(), System.currentTimeMillis() - t0, res.body().length());
        }
    }

    // ==================== ⑥ 暗雷 1 正面回归（附录 D #12） ====================

    @Test
    @Order(5)
    @DisplayName("⑥ 附录 D #12：审计表真的挡住 Replay（判否消息永不被补投）")
    void auditedMessageIsNeverReplayed() throws Exception {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate("DELETE FROM graphiti_write_audit WHERE message_id = '" + BLOCKED_MESSAGE_ID + "'");
            st.executeUpdate("DELETE FROM chat_message WHERE id = '" + BLOCKED_MESSAGE_ID + "'");
            // 造一条满足 selectReplayTargets 全部条件的候选（够旧 + dispatched_at 为 NULL）
            st.executeUpdate("INSERT INTO chat_message (id, session_id, role, content, created_at) VALUES ('"
                    + BLOCKED_MESSAGE_ID + "', '" + SESSION_ID + "', 'user', '今天天气不错随便聊两句', "
                    + "NOW() - INTERVAL 10 MINUTE)");
        }

        // ── 控制组：没有审计行时，它必须「被看见」（否则下面的实验组毫无意义）
        assertEquals(1, replayCandidateCount(),
                "控制组失败：该消息本就该落进 Replay 候选集，否则这个回归测不出任何东西");
        assertEquals(1, stateMapper.selectReplayTargets(500).stream()
                        .filter(t -> BLOCKED_MESSAGE_ID.equals(t.getMessageId())).count(),
                "控制组失败：真实 mapper 捞不到它（顺带也验证了修复排序规则后该 SQL 不再抛 1267）");

        // ── 实验组：把它打成终态（模拟 MemorySignalJudge 判否）
        auditMapper.insertIgnore(PROBE_USER, BLOCKED_MESSAGE_ID,
                GraphitiAuditReason.JUDGE_REJECTED.name(), "今天天气不错随便聊两句", "B1 诊断测试注入");

        assertEquals(0, replayCandidateCount(),
                "★ 暗雷 1 正面回归失败：写审计表后它仍留在 Replay 候选集 → "
                        + "NOT EXISTS(审计表) 漏了 → 图谱会被判否的闲聊灌垃圾（前门拦、后门灌）");
        assertEquals(0, stateMapper.selectReplayTargets(500).stream()
                        .filter(t -> BLOCKED_MESSAGE_ID.equals(t.getMessageId())).count(),
                "★ 真实 mapper 也必须挡住它 —— 这条断言直接钉死生产路径，而不只是一段复制的 SQL");
    }

    /**
     * 与 {@code selectReplayTargets} 的 WHERE 子句同源，只把 {@code LIMIT} 换成「只认探针消息」。
     * 之所以复制一遍而不是直接调 mapper：本查询要的是「这一条在不在候选集里」的确切答案，
     * 而 mapper 返回的是受 LIMIT 约束的列表，在有 391 条真实历史消息的库里无法反推单条结论。
     */
    private int replayCandidateCount() throws Exception {
        return countRows("SELECT COUNT(1) FROM chat_message c "
                + "JOIN chat_session s ON s.id = c.session_id JOIN book b ON b.id = s.book_id "
                + "WHERE c.deleted = 0 AND s.deleted = 0 AND b.deleted = 0 AND c.role = 'user' "
                + "AND c.graphiti_synced_at IS NULL AND c.graphiti_dispatched_at IS NULL "
                + "AND NOT EXISTS (SELECT 1 FROM graphiti_write_audit a WHERE a.message_id = c.id) "
                + "AND c.created_at < NOW() - INTERVAL 5 MINUTE "
                + "AND c.id = '" + BLOCKED_MESSAGE_ID + "'");
    }

    // ==================== ⑦ 清理 ====================

    @AfterAll
    static void cleanupNote() {
        System.out.println("""

                ==========================================================================================
                诊断结束。清理由 _b1_e2e.mjs cleanup 完成：
                  ① 删两条夹具消息（b1probe…003 / …004）+ 会话 + 知识库 + 审计行
                  ② DELETE /group/user_999999（把探针用户的图谱整个删掉）
                之所以不放在 @AfterAll：被测的「落库确认」本身是异步的，
                把清理放进测试生命周期会在失败时把现场证据一起抹掉。
                ==========================================================================================""");
    }

    // ==================== 辅助 ====================

    /** ⚠️ 用 Statement 而非 PreparedStatement：本方法的入参全部是编译期常量拼接，
     *  没有注入面；用 PreparedStatement 反而要求调用点记得 setXxx，漏设就报
     *  「No value specified for parameter 1」这种与业务无关的噪音（已踩过）。 */
    private int countRows(String sql) throws Exception {
        try (Connection c = dataSource.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private String scalar(String column) throws Exception {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT " + column + " FROM chat_message WHERE id = ?")) {
            ps.setString(1, MESSAGE_ID);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private String dispatchedAt() throws Exception {
        return scalar("graphiti_dispatched_at");
    }

    private String syncedAt() throws Exception {
        return scalar("graphiti_synced_at");
    }

    private String episodeUuid() throws Exception {
        return scalar("graphiti_episode_uuid");
    }

    private boolean isNullOfDispatched() {
        try {
            return dispatchedAt() == null;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private boolean isNullOfSynced() {
        try {
            return syncedAt() == null;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private int auditRows() throws Exception {
        return countRows("SELECT COUNT(1) FROM graphiti_write_audit WHERE message_id = '" + MESSAGE_ID + "'");
    }

    private void assertNullOfDispatched(String message) {
        try {
            assertTrue(isNullOfDispatched(), message);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void awaitTrue(String what, Duration timeout, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(1_000);
        }
        throw new AssertionError("等待超时：" + what);
    }
}
