-- ============================================================================
-- Phase 4 · B1 写入链 数据面变更（设计文档 §3.8 / §7.4）
--
-- 执行方式：手动执行（工程未接 Flyway/Liquibase）。
-- 幂等说明：MySQL 8 不支持 ADD COLUMN IF NOT EXISTS，本脚本按「一次性执行」编写；
--          重复执行会报 1060（Duplicate column）/ 1061（Duplicate key name），属预期。
-- 顺序说明：先加列，后建审计表。审计表被对账 SQL 的 NOT EXISTS 子查询依赖，
--          建表必须先于应用启动（否则 Confirm/Replay 两个任务每轮抛 SQL 异常）。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- ① chat_message 加 4 列 + 2 索引
--
-- 四个字段的职责（§3.8 双字段状态机）：
--   graphiti_dispatched_at  既是「投递时刻」的审计事实，又是 CAS 占位的互斥锁凭证。
--                           它有【三种】互不相同的语义，任何把它单独当作「待投递」的
--                           SQL 都是错的（暗雷 1）：
--                             ① IS NULL        = 尚未投递（正常待投 / 熔断跳过 / 扫描漏投）
--                             ② IS NULL 且命中审计表 = 判否 / 毒消息 / 重试耗尽（终态）
--                             ③ IS NOT NULL    = 已投递（<3min 为投递中，>3min 为可接管）
--   graphiti_synced_at      确认落库时刻，只有对账任务能写。
--   graphiti_confirm_rounds 对账未命中累计轮次。没有它，「窗口足够大但那条就是永远
--                           落不了库」的消息会被无限重投（防无限重投的另一半）。
--   graphiti_episode_uuid   确认命中时顺手回填的服务端 uuid。服务端 uuid 事前不可知
--                           （§3.5.2），而遗忘权的 DELETE /episode/{uuid} 与记忆溯源都需要它。
-- ---------------------------------------------------------------------------
ALTER TABLE chat_message
    ADD COLUMN graphiti_dispatched_at   DATETIME     NULL COMMENT '投递到 Graphiti 的时刻（CAS 占位成功时写入）',
    ADD COLUMN graphiti_synced_at       DATETIME     NULL COMMENT '确认落库到图谱的时刻（对账任务写入）',
    ADD COLUMN graphiti_confirm_rounds  INT          NOT NULL DEFAULT 0
        COMMENT '对账未命中的累计轮次；达上限即隔离进审计表，防无限重投',
    ADD COLUMN graphiti_episode_uuid    VARCHAR(64)  NULL
        COMMENT '确认命中时回填的服务端 episode uuid（遗忘权 DELETE /episode/{uuid} 与记忆溯源）',
    ADD INDEX idx_graphiti_pending (graphiti_synced_at, graphiti_dispatched_at),
    ADD INDEX idx_graphiti_episode_uuid (graphiti_episode_uuid);

-- ---------------------------------------------------------------------------
-- ② 新建 graphiti_write_audit —— 「所有永不（重）投递的终态集合」
--
-- 语义（唯一一句话）：审计表 = 终态隔离区。
--   四类终态：JUDGE_REJECTED / POISON / RETRY_EXHAUSTED / CONFIRM_TIMEOUT
--
-- ★ 为什么 message_id 要 UNIQUE：
--   ① 它同时是 Confirm 与 Replay 两条 SQL 的 NOT EXISTS 子查询键，UNIQUE 索引让该子查询
--      退化为唯一索引查找；
--   ② 天然防同一消息被重复隔离（INSERT IGNORE 即可幂等）。
--
-- ★ 为什么必须独立建表而不是复用 user_semantic_memory：
--   旧表没有 message_id / reason / content_snapshot / created_at 这四项，
--   复用等于「改到面目全非 + 留一个会误导后来者的表名」（§7.3 选项乙）。
--
-- ⚠️ 语义切割：本表是审计 / 隔离日志，不是记忆源，不违反「L2 唯一真相源」铁律。
--    运维红利：删掉某一行审计记录 = 该消息重新纳入投递（dispatched_at 已为 NULL 的
--    由 GraphitiReplayJob 接管）—— 人工恢复不需要写任何工具。
--
-- ★★ COLLATE 必须显式写 utf8mb4_unicode_ci，不能只写 DEFAULT CHARSET utf8mb4：
--    本库既有的 book / chat_session / chat_message 三表都是 utf8mb4_unicode_ci
--    （RuoYi 建库时的老约定），而 MySQL 8 的 utf8mb4 默认排序规则是 utf8mb4_0900_ai_ci。
--    一旦漏写，Confirm / Replay 两条 SQL 里的 `a.message_id = c.id` 会因两侧排序规则
--    不同而直接抛 ERROR 1267（Illegal mix of collations）——
--    后果不是"精度下降"而是【对账任务每轮必炸】→ 消息永远确认不了 → 全部走到
--    maxRounds 被误判成 CONFIRM_TIMEOUT 隔离。2026-10-13 实机诊断测试已复现此错。
--    （注意：用绑定变量比 @Param 的 existsByMessageId 不会报错 —— 参数的可强制性为 4，
--      列胜出，所以只有"两个列直接比较"的子查询才会暴露这个问题，更难在自查中发现。）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS graphiti_write_audit
(
    id               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    user_id          VARCHAR(64)  NULL COMMENT '归属用户（冗余存储，便于按用户排查）',
    message_id       VARCHAR(64)  NOT NULL COMMENT 'chat_message.id（外键语义，本工程不建物理外键）',
    reason           VARCHAR(32)  NOT NULL COMMENT '终态原因：JUDGE_REJECTED / POISON / RETRY_EXHAUSTED / CONFIRM_TIMEOUT',
    content_snapshot TEXT         NULL COMMENT '原文快照（P7 层 4 抽样的数据源）',
    detail           VARCHAR(512) NULL COMMENT '补充说明（HTTP 码、轮次等，不得含敏感数据）',
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '隔离时刻',
    PRIMARY KEY (id),
    UNIQUE KEY uk_graphiti_audit_message (message_id),
    KEY idx_graphiti_audit_reason (reason, created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='Graphiti 写入终态审计表：判否/毒消息/重试耗尽/对账超限';

-- ---------------------------------------------------------------------------
-- ②b 已建表但漏写 COLLATE 时的补救（幂等，可重复执行）
--
-- 场景：表先按 utf8mb4_0900_ai_ci 建了出来，之后才发现对账 SQL 报 1267。
-- 该语句同时修正表级与全部字符列级排序规则。
-- 确认当前值：SELECT TABLE_COLLATION FROM information_schema.TABLES
--              WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'graphiti_write_audit';
-- ---------------------------------------------------------------------------
ALTER TABLE graphiti_write_audit CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------------
-- ③ RabbitMQ 运维提示（不在 SQL 内执行，记录于此避免遗漏）
--
-- ⚠️ graphiti.write.queue 声明为优先级队列（x-max-priority = 10）。
--    队列参数的变更会导致 queue.declare 返回 406 PRECONDITION_FAILED。
--    因此任何后续「调参」必须走：
--      ① 停消费者 → ② 确认 graphiti.write.queue 为空 → ③ rabbitmqctl delete_queue
--      → ④ 启动应用重新声明 → ⑤ 恢复消费者。
--    首次部署队列不存在，无此问题。
-- ---------------------------------------------------------------------------
