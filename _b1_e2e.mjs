// Phase 4 · B1 端到端实机验证驱动（附录 D 核心项）
//
// 目标：用【一次性夹具】跑通完整写入链，且不留任何污染：
//   fixture  建 book + chat_session + chat_message（探针用户 999999）
//   fire     向 graphiti.scan.queue 直投一条 GraphitiScanMessage（跳过 5 分钟 delay）
//            → ScanConsumer 取窗 → 判定（命中高危词"过敏"走规则快通道）→ 投 write.queue
//            → WriteConsumer CAS 占位 → POST Graphiti 拿 202 → ack
//            → ConfirmJob（测试期 20s 一轮）从 /episodes 命中 name=cm_<id> → markSynced + 回填 uuid
//   check    读回 chat_message 的四个 graphiti_* 字段 + 审计表 + /episodes 实测
//   cleanup  删测试行 + 删图谱 group
//
// 用法：node _b1_e2e.mjs fixture | fire | check | episodes | cleanup | introspect
const DOCKER = 'http://192.168.88.130:2375';
const MYSQL_CONTAINER = 'mysql';
const DB = 'dynamic_brain';
const MQ_API = process.env.MQ_API || 'http://192.168.88.130:15672';
const MQ_USER = process.env.MQ_USER || 'root';
const MQ_PASS = process.env.MQ_PASS || 'root';
const GRAPHITI = process.env.GRAPHITI || 'http://192.168.88.130:8088';

const PROBE_USER = '999999';
// ⚠️ book.id 是 varchar(32)：夹具 ID 必须 <= 32 字符，否则 INSERT 报 1406（Data too long）
const BOOK_ID = 'b1probe0000000000000000000000001';
const SESSION_ID = 'b1probe0000000000000000000000002';
const MESSAGE_ID = 'b1probe0000000000000000000000003';
// 附录 D #12 的候选消息（被打成 JUDGE_REJECTED 终态后必须永不被补投）
const BLOCKED_MESSAGE_ID = 'b1probe0000000000000000000000004';
const CONTENT = '我对花生过敏，绝对不能吃花生，请务必记住。';

// ---------------- MySQL（经 Docker exec） ----------------
const dapi = async (p, i) => {
  const r = await fetch(`${DOCKER}${p}`, { ...i, headers: { 'Content-Type': 'application/json', ...(i?.headers || {}) } });
  const t = await r.text();
  if (!r.ok) throw new Error(`${r.status} ${t}`);
  return t ? JSON.parse(t) : null;
};
const draw = async (p, i) => (await fetch(`${DOCKER}${p}`, { ...i, headers: { 'Content-Type': 'application/json', ...(i?.headers || {}) } })).arrayBuffer().then(Buffer.from);
const demux = (b) => {
  const parts = [];
  let i = 0;
  while (i + 8 <= b.length) {
    const len = b.readUInt32BE(i + 4);
    if (len <= 0 || i + 8 + len > b.length) break;
    parts.push(b.subarray(i + 8, i + 8 + len));
    i += 8 + len;
  }
  return Buffer.concat(parts.length ? parts : [b]).toString('utf8');
};
const mysql = async (sql) => {
  const cr = await dapi(`/containers/${encodeURIComponent(MYSQL_CONTAINER)}/exec`, {
    method: 'POST',
    body: JSON.stringify({
      AttachStdout: true,
      AttachStderr: true,
      Cmd: ['mysql', '-uroot', '-proot', '--default-character-set=utf8mb4', '-N', '-B', '-e', sql, DB],
    }),
  });
  const out = demux(await draw(`/exec/${cr.Id}/start`, { method: 'POST', body: JSON.stringify({ Detach: false, Tty: false }) }));
  return out
    .replace(/\r/g, '')
    .split('\n')
    .filter((l) => l && !l.includes('Using a password on the command line'))
    .join('\n');
};

// ---------------- RabbitMQ ----------------
const mq = async (path, init) => {
  const r = await fetch(`${MQ_API}${path}`, {
    ...init,
    headers: { Authorization: 'Basic ' + Buffer.from(`${MQ_USER}:${MQ_PASS}`).toString('base64'), 'Content-Type': 'application/json', ...(init?.headers || {}) },
  });
  const t = await r.text();
  if (!r.ok) throw new Error(`${path} → ${r.status} ${t.slice(0, 200)}`);
  return t ? JSON.parse(t) : null;
};
const publishToQueue = (queue, payload, headers) =>
  mq('/api/exchanges/%2F/amq.default/publish', {
    method: 'POST',
    body: JSON.stringify({
      properties: { delivery_mode: 2, contentType: 'application/json', ...(headers ? { headers } : {}) },
      routing_key: queue,
      payload,
      payload_encoding: 'string',
    }),
  });

const notNullCols = async (table) =>
  mysql(
    `SELECT COLUMN_NAME, COLUMN_TYPE, IFNULL(COLUMN_DEFAULT,'~NULL~'), EXTRA FROM information_schema.COLUMNS ` +
      `WHERE TABLE_SCHEMA='${DB}' AND TABLE_NAME='${table}' AND IS_NULLABLE='NO' AND COLUMN_DEFAULT IS NULL AND EXTRA NOT LIKE '%auto_increment%';`
  );

const cmd = process.argv[2];

const main = async () => {
  if (cmd === 'introspect') {
    for (const t of ['book', 'chat_session', 'chat_message']) {
      console.log(`--- ${t} 的「非空且无默认值」列（INSERT 必须显式给值） ---`);
      console.log((await notNullCols(t)) || '(无)');
      console.log();
    }
    return;
  }

  if (cmd === 'fixture') {
    await mysql(`DELETE FROM chat_message WHERE id='${MESSAGE_ID}'; DELETE FROM chat_session WHERE id='${SESSION_ID}'; DELETE FROM book WHERE id='${BOOK_ID}';`);
    console.log('已清理旧夹具（若有）');
    // ★ 写语句的返回必须原样打印：静默的 DDL/DML 失败是排查成本最高的一类问题
    const writeOut = await mysql(
      `INSERT INTO book (id, user_id, name) VALUES ('${BOOK_ID}', '${PROBE_USER}', '__B1_PROBE__'); ` +
        `INSERT INTO chat_session (id, book_id) VALUES ('${SESSION_ID}', '${BOOK_ID}'); ` +
        `INSERT INTO chat_message (id, session_id, role, content) VALUES ('${MESSAGE_ID}', '${SESSION_ID}', 'user', '${CONTENT}');`
    );
    console.log(writeOut.trim() ? `INSERT 输出：${writeOut.trim()}` : 'INSERT 输出：(无报错)');
    console.log('夹具已建立。复核（应能通过 chat_message → chat_session → book 三段 JOIN 取到 userId）：');
    console.log(
      await mysql(
        `SELECT c.id, c.role, c.created_at, c.graphiti_dispatched_at, c.graphiti_synced_at, b.user_id ` +
          `FROM chat_message c JOIN chat_session s ON s.id=c.session_id JOIN book b ON b.id=s.book_id ` +
          `WHERE c.id='${MESSAGE_ID}';`
      )
    );
    return;
  }

  if (cmd === 'fire') {
    // GraphitiScanMessage 是 record，Jackson 需要 __TypeId__ 头（TYPE_ID 优先级）
    const body = JSON.stringify({
      msgId: 'b1-probe-' + Date.now(),
      sessionId: SESSION_ID,
      userId: PROBE_USER,
      watermark: null,
    });
    const res = await publishToQueue('graphiti.scan.queue', body, {
      __TypeId__: 'com.yangmf.mini_nodepad.mq.GraphitiScanMessage',
      contentType: 'application/json',
    });
    console.log(`已投 GraphitiScanMessage → graphiti.scan.queue : ${JSON.stringify(res)}`);
    console.log(`载荷：${body}`);
    return;
  }

  if (cmd === 'check') {
    console.log('--- chat_message 四字段状态（探针消息 + 附录 D #12 的候选消息） ---');
    console.log(
      (await mysql(
        `SELECT id, graphiti_dispatched_at, graphiti_synced_at, graphiti_confirm_rounds, graphiti_episode_uuid ` +
          `FROM chat_message WHERE id IN ('${MESSAGE_ID}','${BLOCKED_MESSAGE_ID}');`
      )) || '(行不存在)'
    );
    console.log('\n--- 审计表 ---');
    console.log(
      (await mysql(
        `SELECT message_id, reason, LEFT(detail,60) AS detail FROM graphiti_write_audit ` +
          `WHERE message_id IN ('${MESSAGE_ID}','${BLOCKED_MESSAGE_ID}');`
      )) || '(空)'
    );
    console.log(
      `\n判读：#12 的候选消息（…004）应当【只在审计表出现、dispatched_at 恒为 NULL】—— ` +
        `这才是「暗雷 1 被 NOT EXISTS 挡住」的正面证据。`
    );
    return;
  }

  if (cmd === 'cleanup') {
    console.log('--- 删除图谱 group ---');
    try {
      const r = await fetch(`${GRAPHITI}/group/user_${PROBE_USER}`, { method: 'DELETE' });
      console.log(`DELETE /group/user_${PROBE_USER} → ${r.status} ${(await r.text()).slice(0, 200)}`);
    } catch (e) {
      console.log(`删除 group 失败（可手动清 Neo4j）：${e.message}`);
    }
    console.log('\n--- 删除 MySQL 夹具行 ---');
    await mysql(
      `DELETE FROM graphiti_write_audit WHERE message_id IN ('${MESSAGE_ID}','${BLOCKED_MESSAGE_ID}'); ` +
        `DELETE FROM chat_message WHERE id IN ('${MESSAGE_ID}','${BLOCKED_MESSAGE_ID}'); ` +
        `DELETE FROM chat_session WHERE id='${SESSION_ID}'; ` +
        `DELETE FROM book WHERE id='${BOOK_ID}';`
    );
    console.log('已清理。复核残留（三个计数都应为 0）：');
    console.log(
      (await mysql(
        `SELECT (SELECT COUNT(*) FROM chat_message WHERE id IN ('${MESSAGE_ID}','${BLOCKED_MESSAGE_ID}')) AS msg, ` +
          `(SELECT COUNT(*) FROM chat_session WHERE id='${SESSION_ID}') AS sess, ` +
          `(SELECT COUNT(*) FROM book WHERE id='${BOOK_ID}') AS book, ` +
          `(SELECT COUNT(*) FROM graphiti_write_audit WHERE message_id IN ('${MESSAGE_ID}','${BLOCKED_MESSAGE_ID}')) AS audit;`
      )) || '(查询失败)'
    );
    return;
  }

  if (cmd === 'episodes') {
    const g = `user_${PROBE_USER}`;
    for (const n of [20, 200]) {
      // ⚠️ 路径形式 /episodes/{group_id}?last_n=N —— 查询参数形式 /episodes?group_id=…
      //    会返回 404，看起来像"没有数据"，实际只是 URL 写错了（已实测踩过）。
      const t0 = Date.now();
      const res = await fetch(`${GRAPHITI}/episodes/${g}?last_n=${n}`);
      const text = await res.text();
      console.log(`GET /episodes/${g}?last_n=${n} → status=${res.status} 耗时=${Date.now() - t0}ms 体积=${text.length} 字节`);
      if (res.ok) {
        const arr = JSON.parse(text);
        console.log(`  条数=${arr.length}`);
        console.log(`  首条：name=${JSON.stringify(arr[0]?.name)} created_at=${arr[0]?.created_at}`);
        // 用时间戳比较而非字符串比较：字符串比较只在「全部同 offset 同格式」时才等价
        const ts = arr.map((e) => Date.parse(e.created_at));
        const isDesc = ts.every((v, i) => i === 0 || ts[i - 1] >= v);
        console.log(isDesc ? '  ✓ 排序键仍是 created_at 倒序（#2b 回归通过）' : '  ✗ 排序键已变：§3.6.1 公式前提失效！');
        console.log(`  字段清单：${Object.keys(arr[0] || {}).join(', ')}`);
      } else {
        console.log(`  返回体：${text.slice(0, 120)}`);
      }
    }
    return;
  }

  console.log('用法：node _b1_e2e.mjs fixture | fire | check | episodes | cleanup | introspect');
};

main().catch((e) => {
  console.error('失败：', e.message);
  process.exit(1);
});
