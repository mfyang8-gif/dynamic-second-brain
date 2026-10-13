package com.yangmf.mini_nodepad;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * <b>静态约束测试</b> —— 不跑逻辑，只读源码文本做断言。
 *
 * <p>为什么需要这一层：B1 实机验证抓到的两个 P0（{@code LocalDateTime.MIN} 超出 MySQL
 * DATETIME 定义域、审计表漏写 COLLATE 导致跨表排序规则冲突）都不是"某段逻辑写错了"，
 * 而是<b>结构性的、静默的</b>：编译通过、启动成功、常规操作不报错，只在特定输入下把
 * 整条写入链变成"只投不认"。这类缺陷无法用行为测试拦住（除非每次都打真库），
 * 但可以用文本断言钉死"这种写法不许再出现"。
 *
 * <p>它的代价极低（毫秒级、零依赖），收益是让两个 P0 无法回归。
 */
@DisplayName("L0-5 静态约束 —— 让两个 P0 与三条铁律无法回归")
class GraphitiStaticGuardTest {

    private static final Path MAIN_JAVA = Path.of("src/main/java/com/yangmf/mini_nodepad");
    private static final Path DDL =
            Path.of("src/main/resources/db/phase4-b1-graphiti-write-chain.sql");
    private static final Path APPLICATION_YAML = Path.of("src/main/resources/application.yaml");

    private static final Path STATE_MAPPER = MAIN_JAVA.resolve("mapper/GraphitiWriteStateMapper.java");
    private static final Path WRITE_CONSUMER = MAIN_JAVA.resolve("mq/GraphitiWriteConsumer.java");
    private static final Path SCAN_CONSUMER = MAIN_JAVA.resolve("mq/GraphitiScanConsumer.java");
    private static final Path SCAN_PRODUCER = MAIN_JAVA.resolve("mq/GraphitiScanProducer.java");
    private static final Path REPLAY_JOB = MAIN_JAVA.resolve("job/GraphitiReplayJob.java");
    private static final Path CONFIRM_JOB = MAIN_JAVA.resolve("job/GraphitiConfirmJob.java");
    private static final Path MQ_CONFIG = MAIN_JAVA.resolve("mq/GraphitiMqConfig.java");
    private static final Path WRITE_MESSAGE = MAIN_JAVA.resolve("mq/GraphitiWriteMessage.java");
    private static final Path LEGACY_MQ_CONFIG = MAIN_JAVA.resolve("mq/RabbitSemanticConfig.java");

    // ==================== ① P0-1 回归：MySQL DATETIME 定义域 ====================

    @Test
    @DisplayName("★ P0-1 回归：全 main 源码内零处把 LocalDateTime.MIN / Instant.MIN 用作值")
    void noJavaExtremumLeaksIntoCode() {
        // 这两个常量是 Java 概念，不是 SQL 概念：MySQL DATETIME 只覆盖 1000-01-01 ~ 9999-12-31，
        // 绑定 LocalDateTime.MIN（-999999999-01-01）会抛 error 1525 Incorrect DATETIME value。
        // 表现为「每个新会话的首次扫描必炸 → 进 DLQ → 记忆对新会话完全不生成」。
        List<String> offenders = new ArrayList<>();
        for (Path file : javaFiles()) {
            String code = stripComments(read(file));
            String[] lines = code.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                if (line.contains("LocalDateTime.MIN") || line.contains("Instant.MIN")
                        || line.contains("LocalDateTime.MAX") || line.contains("Instant.MAX")) {
                    offenders.add(file.getFileName() + ":" + (i + 1) + "  " + line.strip());
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                () -> """
                       源码（非注释）里出现了 Java 的极值常量。这是 P0-1 的成因：
                       LocalDateTime.MIN = -999999999-01-01，超出 MySQL DATETIME 定义域（1000-01-01 ~ 9999-12-31），
                       绑定进 SQL 会抛 error 1525，且只在「无水位」分支触发 —— 即每个新会话的首次扫描必炸 → 进 DLQ
                       → 对外只表现为「记忆一直不生成」。请改用 EPOCH_FLOOR（1970-01-01）。

                       命中处：
                       """ + String.join("\n", offenders));
    }

    @Test
    @DisplayName("★ P0-1 伴生约束：IT 用的基线日期不得早于 MySQL DATETIME 下界")
    void epochFloorIsWithinMysqlRange() {
        String code = stripComments(read(SCAN_CONSUMER));
        assertTrue(code.contains("EPOCH_FLOOR"),
                "扫描层的窗口下界常量应名为 EPOCH_FLOOR，便于审计");
        Matcher m = Pattern.compile("EPOCH_FLOOR\\s*=\\s*LocalDateTime\\.of\\((\\d{4})").matcher(code);
        assertTrue(m.find(), "未找到 EPOCH_FLOOR 的字面量定义 —— 若改成配置项请同步本断言");
        int year = Integer.parseInt(m.group(1));
        assertTrue(year >= 1000 && year <= 9999,
                () -> "EPOCH_FLOOR 落在 MySQL DATETIME 定义域之外 | year=" + year);
    }

    // ==================== ② P0-2 回归：跨表排序规则 ====================

    @Test
    @DisplayName("★ P0-2 回归：审计表建表必须显式 COLLATE = utf8mb4_unicode_ci")
    void auditTableDeclaresCollationExplicitly() {
        String ddl = read(DDL);
        int createIdx = ddl.indexOf("CREATE TABLE IF NOT EXISTS graphiti_write_audit");
        assertTrue(createIdx >= 0, "DDL 里找不到审计表建表语句");

        int endIdx = ddl.indexOf(";", createIdx);
        assertTrue(endIdx > createIdx, "建表语句未以分号结束，无法界定范围");
        String statement = ddl.substring(createIdx, endIdx);

        assertTrue(statement.contains("COLLATE"),
                """
                       审计表建表语句没有显式 COLLATE。这是 P0-2 的成因：
                       只写 DEFAULT CHARSET utf8mb4 会落到 MySQL 8 的默认 utf8mb4_0900_ai_ci，
                       而本库既有表全是 utf8mb4_unicode_ci（RuoYi 老约定）。
                       → a.message_id = c.id 这类「列 vs 列」比较抛 error 1267 Illegal mix of collations
                       → 对账 / 补投每轮必炸 → 消息永远确认不了 → 全走 maxRounds 被误判 CONFIRM_TIMEOUT
                       → 写入链静默退化成「只投不认」。
                       ⚠️ 自查极难发现：existsByMessageId（绑定变量 vs 列）不会报错（参数 coercibility=4 > 列=2），
                       只有「列 vs 列」才暴露。""");

        assertTrue(statement.contains("utf8mb4_unicode_ci"),
                "COLLATE 值必须是 utf8mb4_unicode_ci（与本库既有表一致），不能是 utf8mb4_0900_ai_ci");
    }

    @Test
    @DisplayName("★ P0-2 补救：DDL 里必须带幂等的 COLLATE 修正语句（已建表也能救）")
    void auditTableHasIdempotentCollationRepair() {
        String ddl = read(DDL);
        assertTrue(ddl.contains("ALTER TABLE graphiti_write_audit CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci"),
                """
                        缺少幂等补救语句。原因：建表脚本一旦在旧版本执行过，后续修改 CREATE 语句
                        对**已存在的表**毫无作用（CREATE TABLE IF NOT EXISTS 会静默跳过）——
                        必须有 ALTER ... CONVERT TO 才能把线上表救回来。""");
    }

    // ==================== ③ 铁律 2：判否消息不得被捞回 ====================

    @Test
    @DisplayName("★ 铁律 2：三条取数 SQL 都必须带 NOT EXISTS(审计表)")
    void allSelectionQueriesExcludeAuditedMessages() {
        String sql = stripComments(read(STATE_MAPPER));
        String needle = "NOT EXISTS (SELECT 1 FROM graphiti_write_audit";

        int count = countOccurrences(sql, needle);
        assertEquals(3, count,
                () -> """
                       NOT EXISTS(审计表) 应恰好出现 3 次：selectScanWindow / selectConfirmTargets / selectReplayTargets 各一。
                       实测 %d 次。
                       ★ 少一处即回归：审计表是「永不（重）投递」的终态集合。少了它，
                         判否消息（dispatched_at 恒为 NULL）会被 ReplayJob 每轮反复捞起 —— 
                         「只延迟不丢弃」就变成了「只延迟不丢弃、而且永远不放弃」。
                       请勿把本断言改成 >= 或 <= ；新增取数 SQL 时请同步更新数字，这是有意的摩擦。"""
                        .formatted(count));

        // 三条 SQL 的名字必须都能在源码里找到 —— 防止有人把其中一条删了却没触发 count 变化之外的意外
        for (String method : List.of("selectScanWindow", "selectConfirmTargets", "selectReplayTargets")) {
            assertTrue(sql.contains(method), "取数方法不见了: " + method);
        }
    }

    @Test
    @DisplayName("★ 暗雷 1 结构性修复：对账只认 dispatched_at 非空（判否消息进不了对账视野）")
    void confirmAndReplayRequireDispatchedAtNotNull() {
        String sql = stripComments(read(STATE_MAPPER));
        int idx = sql.indexOf("selectConfirmTargets");
        assertTrue(idx > 0);
        // 向上取该 SQL 正文（注解在方法签名之前）
        String around = sql.substring(Math.max(0, idx - 2600), idx);
        assertTrue(around.contains("c.graphiti_dispatched_at IS NOT NULL"),
                """
                        selectConfirmTargets 缺少 dispatched_at IS NOT NULL。
                        dispatched_at IS NULL 有【三种】语义：① 尚未投递 ② 判否（已进审计表）
                        ③ 毒消息/重试耗尽（已进审计表）。对账只应处理 ① 已投递的，
                        少了这个谓词会把 ② ③ 也纳入对账 —— 而它们永远不会有 episode → 每轮都算未命中
                        → 走满 maxRounds 打一批假 CONFIRM_TIMEOUT。""");
    }

    @Test
    @DisplayName("★ 对账最小年龄门槛存在且是显式 INTERVAL（当前硬编码 3 分钟，无配置项 —— 见下）")
    void confirmGateIsExplicitInterval() {
        String sql = stripComments(read(STATE_MAPPER));
        int idx = sql.indexOf("selectConfirmTargets");
        String around = sql.substring(Math.max(0, idx - 2600), idx);
        assertTrue(around.contains("NOW() - INTERVAL"),
                """
                       对账 SQL 缺少最小年龄门槛。补投层与确认层共用 dispatched_at，
                       若没有入门槛，刚投出去（还在服务端排队抽取）的消息会被立刻判「未命中」并重投。
                       门槛本身是必要的；但 ⚠️ 它目前是 SQL 里的**魔数**，与 graphiti.confirm.interval-ms
                       没有任何代码级关联 —— 见本类 confirmGateIsBelowJobInterval 的说明。""");
        assertTrue(around.contains("INTERVAL 3 MINUTE"),
                """
                        门槛值变了。请一并复核这两件事：
                        ① 它与 graphiti.confirm.interval-ms（默认 5 分钟）的相对关系：门槛必须 < 轮询间隔，
                           否则消息会等到「间隔 + 门槛」才被首次对账，无谓地拉长落库确认时延；
                        ② 附录 D #7 实测的落库耗时（≈12.3s，历史记录 15~45s）：门槛必须显著大于它，
                           否则会把「还在落库」误判成「落库失败」。""");
    }

    // ==================== ④ 优先级：必须显式下发 ====================

    @Test
    @DisplayName("★ 每个投往「带优先级的目的地」的 convertAndSend 都必须显式 setPriority")
    void everyPublishSetsPriorityExplicitly() {
        // ⚠️ 范围限定（踩过一次的坑）：只有声明了 x-max-priority 的目的地才需要设优先级。
        //    graphiti.scan.delay.queue 是 TTL 延时队列、没有 x-max-priority，
        //    给它设优先级不但无效，而且优先级在 dead-letter 到 scan.queue（同样无优先级）后也没有意义。
        //    因此本断言只覆盖 write 侧的四类目的地。
        List<String> priorityCapableDestinations = List.of(
                "EXCHANGE_WRITE", "QUEUE_WRITE", "QUEUE_WRITE_DLQ",
                "QUEUE_RETRY_30S", "QUEUE_RETRY_2MIN", "QUEUE_RETRY_10MIN");

        List<String> offenders = new ArrayList<>();
        int checked = 0;
        for (Path file : List.of(WRITE_CONSUMER, SCAN_CONSUMER, SCAN_PRODUCER, REPLAY_JOB, CONFIRM_JOB)) {
            String[] lines = stripComments(read(file)).split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                if (!lines[i].contains("convertAndSend")) {
                    continue;
                }
                boolean priorityCapable = priorityCapableDestinations.stream().anyMatch(lines[i]::contains);
                if (!priorityCapable) {
                    continue;
                }
                checked++;
                // 投递调用与其 lambda 通常相邻；给 ±8 行窗口足够覆盖换行写法
                int from = Math.max(0, i - 8);
                int to = Math.min(lines.length - 1, i + 8);
                boolean hasPriority = false;
                for (int j = from; j <= to; j++) {
                    if (lines[j].contains("setPriority")) {
                        hasPriority = true;
                        break;
                    }
                }
                if (!hasPriority) {
                    offenders.add(file.getFileName() + ":" + (i + 1) + "  " + lines[i].strip());
                }
            }
        }

        assertTrue(checked >= 4,
                "只识别到 " + checked + " 个「带优先级目的地」的投递点 —— 少于预期的 4 个，"
                        + "说明投递点被重构或常量改名了，请同步本断言的锚点");
        assertTrue(offenders.isEmpty(),
                () -> """
                       ★ 有投往 priority 队列的投递点没有显式 setPriority。后果是静默的：
                         x-max-priority 只在队列**积压**时生效；而 Message 的 priority 默认是 0，
                         自己 new 出来的 Message 不会继承原消息的优先级。
                         → 高危消息失败一次重投后就降级成平民，优先级队列形同虚设，
                           而日志里什么都不会说。命中处：
                       """ + String.join("\n", offenders));
    }

    @Test
    @DisplayName("★ 队列声明与消息常量必须一致：x-max-priority = 10 = PRIORITY_HIGH_RISK")
    void queueMaxPriorityMatchesMessageConstant() {
        String mq = read(MQ_CONFIG);
        assertTrue(mq.contains("x-max-priority"),
                "写入队列未声明 x-max-priority → 优先级插队完全失效（消息带 priority 也没用）");
        assertTrue(Pattern.compile("WRITE_MAX_PRIORITY\\s*=\\s*10").matcher(mq).find(),
                "WRITE_MAX_PRIORITY 应为 10");
        assertTrue(read(WRITE_MESSAGE).contains("PRIORITY_HIGH_RISK = 10"),
                """
                       消息侧的高危优先级必须是 10。两侧不一致时 broker 会**静默截断**：
                       队列声明 10 而消息给 20 → 截成 10；队列声明 5 而消息给 10 → 截成 5。
                       两种都不报错，只表现为「高危消息没有真的插队」。""");
    }

    // ==================== ⑤ 铁律 1：唯一防重防线 ====================

    @Test
    @DisplayName("★ 铁律 1：新链里不得出现 Redis 去重锁（唯一防线是 DB CAS）")
    void noRedisDedupInNewChain() {
        List<String> offenders = new ArrayList<>();
        for (Path file : List.of(WRITE_CONSUMER, SCAN_CONSUMER, REPLAY_JOB, CONFIRM_JOB)) {
            String[] lines = stripComments(read(file)).split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                if (lines[i].contains("setIfAbsent") || lines[i].contains("setnx")) {
                    offenders.add(file.getFileName() + ":" + (i + 1) + "  " + lines[i].strip());
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                () -> """
                       ★ 新链里出现了 Redis 去重锁。这是旧链被删掉的那层（陷阱 9）：
                         消息重试回来后，key 已被自己上一次占住 → 该轮永久漏扫，且不报错。
                         B1 的唯一防重防线是 DB CAS（graphiti_dispatched_at 兼软锁，3 分钟自动放开），
                         它不会把自己锁死（可超时）。命中处：
                       """ + String.join("\n", offenders));
    }

    @Test
    @DisplayName("CAS 必须是「先占位后投递」：casOccupy 在 write() 调用之前")
    void casOccupiesBeforePublish() {
        String code = stripComments(read(WRITE_CONSUMER));
        int casIdx = code.indexOf("casOccupy(");
        int writeIdx = code.indexOf(".write(");
        assertTrue(casIdx > 0 && writeIdx > 0, "找不到 casOccupy 或 write 调用");
        assertTrue(casIdx < writeIdx,
                """
                       CAS 占位必须发生在 POST 之前。反过来（先 POST 后占位）在并发下会给同一条消息发两次，
                       而客户端没有任何幂等键 —— Graphiti 服务端对 episode 零去重（实测），结果是图谱里出现重复事实。""");
    }

    @Test
    @DisplayName("★ 可重试失败必须回滚占位（否则消息被自己的防重机制永久拒收）")
    void retryableFailureRollsBackOccupy() {
        String code = stripComments(read(WRITE_CONSUMER));
        assertTrue(code.contains("rollbackOccupy("),
                """
                       找不到 rollbackOccupy 调用。CAS 占位既是防重软锁，也是「消息已被处理」的标记；
                       一旦某次可重试失败忘了回滚，该消息的 dispatched_at 会永远非空：
                       ① 扫描层的 dispatched_at IS NULL 不再捞它；
                       ② 对账会把它当「已投递未确认」反复计数，直到打 CONFIRM_TIMEOUT；
                       → 消息实际上永久丢失，但对外看起来像「已投递过」。
                       这就是「防重机制把消息拒收」的死锁形态（陷阱 9 的另一面）。""");
    }

    // ==================== ⑥ 常量单一真相 ====================

    @Test
    @DisplayName("★ 字符预算只在一处声明：不得出现硬编码的 4000 / 8000 截断")
    void contentBudgetHasSingleSourceOfTruth() {
        List<String> offenders = new ArrayList<>();
        for (Path file : javaFiles()) {
            String[] lines = read(file).split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                if (line.contains("substring(0, 4000)") || line.contains("substring(0, 8000)")
                        || line.contains("<= 4000") || line.contains("<= 8000")) {
                    offenders.add(file.getFileName() + ":" + (i + 1) + "  " + line.strip());
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                () -> """
                       ★ 出现硬编码的字符预算。扫描层 / 补投层 / 高危快通道是三条**独立**进入
                         graphiti.write.queue 的路径，各自写死一个数字的话，改了一处另两处会静默分叉 ——
                         同一条消息经不同路径进来被截成不同长度，不报错、不可观测，
                         只在人群里表现为「有时候记忆不完整」。
                         唯一真相是 GraphitiWriteMessage.CONTENT_CHAR_BUDGET。命中处：
                       """ + String.join("\n", offenders));
    }

    @Test
    @DisplayName("★ 三处截断实现必须都引用同一个常量（防两条路径行为分叉）")
    void allTruncationSitesReferenceTheSharedConstant() {
        for (Path file : List.of(SCAN_CONSUMER, SCAN_PRODUCER, REPLAY_JOB)) {
            String code = stripComments(read(file));
            assertTrue(code.contains("GraphitiWriteMessage.CONTENT_CHAR_BUDGET"),
                    () -> file.getFileName()
                            + " 没有引用 CONTENT_CHAR_BUDGET（可能自己写了一个私有副本）");
        }
    }

    // ==================== ⑦ MQ 拓扑常量 ====================

    @Test
    @DisplayName("★ 三级重试的 TTL 常量与队列名必须成对且完整（30s / 2min / 10min）")
    void retryTiersAreCompleteAndPaired() {
        String mq = stripComments(read(MQ_CONFIG));
        assertTrue(mq.contains("RETRY_TTL_30S_MS = 30_000"), "30s 档 TTL");
        assertTrue(mq.contains("RETRY_TTL_2MIN_MS = 120_000"), "2min 档 TTL");
        assertTrue(mq.contains("RETRY_TTL_10MIN_MS = 600_000"), "10min 档 TTL");

        assertTrue(Pattern.compile("case\\s+0\\s*->").matcher(mq).find(), "retryQueueOf 缺 0 档");
        assertTrue(Pattern.compile("case\\s+1\\s*->").matcher(mq).find(), "retryQueueOf 缺 1 档");
        assertTrue(mq.contains("default ->"),
                """
                       retryQueueOf 缺 default 分支。档位越界时必须落到最长档 —— 若抛异常，
                       重试路径本身失败，消息会丢失；若落到最短档，会制造高频重试把配额窗口填满。""");

        assertTrue(mq.contains("SCAN_DELAY_MS = 300_000"),
                "扫描静默期应为 5 分钟（与前端提示的「稍后回忆」体感一致）");
        assertTrue(mq.contains("WRITE_MAX_PRIORITY = 10"), "写入队列优先级上限");
    }

    @Test
    @DisplayName("★ 新写入队列必须配 DLX（旧链隐患 1 的结构性修复）")
    void writeQueueHasDeadLetterExchange() {
        String mq = stripComments(read(MQ_CONFIG));
        int idx = mq.indexOf("QueueBuilder.durable(QUEUE_WRITE)");
        assertTrue(idx > 0, "找不到写入队列的队列声明（锚点 QueueBuilder.durable(QUEUE_WRITE)）");
        String around = mq.substring(idx, Math.min(mq.length(), idx + 400));
        assertTrue(around.contains("deadLetterExchange"),
                """
                       写入队列没有配 DLX。ToxicExchange 缺 DLX 会让「重试耗尽的消息」无声消失 ——
                       它们既不在队列里，也没有地方可查，运维只能靠比对 chat_message 行数发现少了东西。""");
    }

    // ==================== ⑧ 旧链反向断言（「别顺手加」也要被钉住）====================

    @Test
    @DisplayName("★ 反向断言：旧链 semantic.scan.queue 的 args 必须为空（加 DLX 是死代码）")
    void legacyScanQueueMustKeepEmptyArguments() {
        String code = stripComments(read(LEGACY_MQ_CONFIG));
        int idx = code.indexOf("QueueBuilder.durable(QUEUE_SCAN)");
        assertTrue(idx > 0, "找不到旧链 scan 队列声明（若已重命名请同步本断言）");
        int end = code.indexOf(";", idx);
        String statement = code.substring(idx, end > idx ? end : code.length());

        assertFalse(statement.contains("deadLetterExchange") || statement.contains("DLX")
                        || statement.contains("x-dead-letter"),
                () -> """
                      ★ 旧链 semantic.scan.queue 被加上了 DLX。请还原。
                        理由（不是懒，是它确实是死代码）：该队列的消费者 SemanticScanConsumer 有
                        catch-all（try/catch 包住全部逻辑），异常永不外泄 → 消息永不被 reject/nack 不回队
                        → 没有任何消息会死信 → 配了 DLX 也只是声明，永远收不到货。
                        更要紧的是它会造成误解：「这里也有 DLX，说明失败消息会被投到 DLQ」——
                        而实际不会，排查时会朝错的方向找。
                        当前语句：""" + statement.strip());
    }

    // ==================== ⑨ 高危词表：空项不得命中一切 ====================

    @Test
    @DisplayName("★ 高危词表不含空白项（contains(\"\") 恒 true 会让优先级机制彻底失效）")
    void highRiskLexiconHasNoBlankEntry() {
        Path lexicon = MAIN_JAVA.resolve("service/HighRiskLexicon.java");
        String code = stripComments(read(lexicon));
        assertTrue(code.contains("isBlank"),
                """
                       HighRiskLexicon 必须显式忽略空白词条（isBlank / trim 后跳过）。
                       否则配置里一旦混入一个空串或空格，contains("") 恒为 true
                       → **所有**消息都走高危快通道 → 优先级插队失去意义，
                         且 5 分钟静默期被绕过，全量消息一次性涌进写入队列。
                       这类配置错误不会报错，只会让熔断与限流保护形同虚设。""");

        // 顺带检查默认词表里没有空项
        Matcher m = Pattern.compile("List\\.of\\((.*?)\\)", Pattern.DOTALL).matcher(code);
        while (m.find()) {
            String args = m.group(1);
            for (String part : args.split(",")) {
                String v = part.strip();
                if (!v.startsWith("\"")) {
                    continue;
                }
                String literal = v.replace("\"", "").strip();
                assertFalse(literal.isEmpty(),
                        "默认高危词表里含空字符串 —— 会让 contains(\"\") 恒真，所有消息都走快通道");
            }
        }
    }

    @Test
    @DisplayName("★ 词表配置项里不得有空项（配置才是空项最可能溜进来的地方）")
    void highRiskLexiconConfigHasNoBlankEntry() {
        List<String> lines = List.of(read(APPLICATION_YAML).split("\n", -1));
        int start = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).strip().startsWith("high-risk-lexicon:")) {
                start = i;
                break;
            }
        }
        assertTrue(start >= 0, "application.yaml 里找不到 high-risk-lexicon 配置项");

        List<String> entries = new ArrayList<>();
        for (int i = start + 1; i < lines.size(); i++) {
            String line = lines.get(i);
            String stripped = line.strip();
            if (!stripped.startsWith("-")) {
                break;
            }
            entries.add(stripped.substring(1).strip());
        }
        assertFalse(entries.isEmpty(), "high-risk-lexicon 是空列表 —— 快通道被关闭（若非本意请补词）");
        for (String entry : entries) {
            assertFalse(entry.isEmpty(),
                    () -> """
                          ★ high-risk-lexicon 里有一个空项（或纯空白项）。后果是灾难性的：
                            HighRiskLexicon.matched() 会对空串做 contains，恒为 true
                            → 每一条消息都命中快通道 → 绕过 5 分钟静默期、全部以 priority=10 涌入写入队列。
                            优先级插队因此失去区分度，背压保护也被绕过。
                          """);
        }
    }

    // ==================== 工具方法 ====================

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return fail("读取源码失败：" + path.toAbsolutePath()
                    + "\n  （静态约束测试依赖工作目录 = 工程根目录；若在 IDE 里执行请确认 working directory）", e);
        }
    }

    /** 去掉块注释与行注释 —— 断言只针对**可执行代码**，注释里的警示文案不算违规。 */
    private static String stripComments(String code) {
        String noBlock = code.replaceAll("(?s)/\\*.*?\\*/", "");
        StringBuilder sb = new StringBuilder(noBlock.length());
        for (String line : noBlock.split("\n", -1)) {
            int idx = line.indexOf("//");
            sb.append(idx >= 0 ? line.substring(0, idx) : line).append('\n');
        }
        return sb.toString();
    }

    private static List<Path> javaFiles() {
        try (Stream<Path> stream = Files.walk(MAIN_JAVA)) {
            return stream.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        } catch (IOException e) {
            return fail("遍历源码目录失败：" + MAIN_JAVA.toAbsolutePath(), e);
        }
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int from = 0;
        while (true) {
            int idx = haystack.indexOf(needle, from);
            if (idx < 0) {
                return count;
            }
            count++;
            from = idx + needle.length();
        }
    }
}
