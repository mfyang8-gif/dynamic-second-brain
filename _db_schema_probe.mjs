// 只读 DB 结构探针（Phase 4 · B1 部署前置校验）
// 用途：确认 chat_message 的 4 个 graphiti_* 列与 graphiti_write_audit 表是否已存在。
// 走 Docker API exec 到 mysql 容器，无需本机 mysql 客户端。
// 用法：node _db_schema_probe.mjs            （默认 192.168.88.130:2375 / 容器 mysql）
//       MYSQL_CONTAINER=xxx node _db_schema_probe.mjs
const DOCKER = process.env.DOCKER_HOST_API || 'http://192.168.88.130:2375';
const CONTAINER = process.env.MYSQL_CONTAINER || 'mysql';
const DB = process.env.MYSQL_DB || 'dynamic_brain';
const USER = process.env.MYSQL_USER || 'root';
const PASS = process.env.MYSQL_PASS || 'root';

const api = async (path, init) => {
  const res = await fetch(`${DOCKER}${path}`, {
    ...init,
    headers: { 'Content-Type': 'application/json', ...(init?.headers || {}) },
  });
  const text = await res.text();
  if (!res.ok) throw new Error(`${res.status} ${text}`);
  return text ? JSON.parse(text) : null;
};

// exec start 返回的是 Docker 多路复用二进制流，不是 JSON —— 必须单独处理。
const apiRaw = async (path, init) => {
  const res = await fetch(`${DOCKER}${path}`, {
    ...init,
    headers: { 'Content-Type': 'application/json', ...(init?.headers || {}) },
  });
  const buf = Buffer.from(await res.arrayBuffer());
  if (!res.ok) throw new Error(`${res.status} ${buf.toString('utf8')}`);
  return buf;
};

/** 剥掉 Docker 多路复用头（8 字节：streamType + 3 保留 + 4 字节大端长度）。 */
const demux = (buf) => {
  const parts = [];
  let i = 0;
  while (i + 8 <= buf.length) {
    const len = buf.readUInt32BE(i + 4);
    if (len <= 0 || i + 8 + len > buf.length) break;
    parts.push(buf.subarray(i + 8, i + 8 + len));
    i += 8 + len;
  }
  return Buffer.concat(parts.length ? parts : [buf]).toString('utf8');
};

const exec = async (sql) => {
  const created = await api(`/containers/${encodeURIComponent(CONTAINER)}/exec`, {
    method: 'POST',
    body: JSON.stringify({
      AttachStdout: true,
      AttachStderr: true,
      Cmd: ['mysql', `-u${USER}`, `-p${PASS}`, '-N', '-B', '-e', sql, DB],
    }),
  });
  if (!created?.Id) throw new Error('exec 创建失败（容器名或 Docker API 不可达）');
  const raw = await apiRaw(`/exec/${created.Id}/start`, {
    method: 'POST',
    body: JSON.stringify({ Detach: false, Tty: false }),
  });
  return demux(raw).replace(/\r/g, '');
};

const main = async () => {
  console.log(`# 目标：${DOCKER} container=${CONTAINER} db=${DB}\n`);

  const cols = await exec(
    "SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT FROM information_schema.COLUMNS " +
      "WHERE TABLE_SCHEMA='" + DB + "' AND TABLE_NAME='chat_message' AND COLUMN_NAME LIKE 'graphiti%' " +
      'ORDER BY ORDINAL_POSITION;'
  );
  console.log('## chat_message 的 graphiti_* 列');
  console.log(cols.trim() || '(无 —— 4 列尚未添加，必须先执行 DDL)');

  const tables = await exec(
    "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA='" + DB + "' " +
      "AND TABLE_NAME IN ('graphiti_write_audit','user_semantic_memory');"
  );
  console.log('\n## 相关表是否存在');
  console.log(tables.trim() || '(均不存在)');

  const idx = await exec(
    "SELECT DISTINCT INDEX_NAME FROM information_schema.STATISTICS WHERE TABLE_SCHEMA='" + DB + "' " +
      "AND TABLE_NAME='chat_message' AND INDEX_NAME LIKE 'idx_graphiti%';"
  );
  console.log('\n## chat_message 的 graphiti 索引');
  console.log(idx.trim() || '(无)');

  const counts = await exec(
    "SELECT CONCAT('chat_message=', (SELECT COUNT(*) FROM chat_message), " +
      "', user 轮次=', (SELECT COUNT(*) FROM chat_message WHERE role='user'));"
  );
  console.log('\n## 数据规模');
  console.log(counts.trim() || '(查询失败)');
};

main().catch((e) => {
  console.error('探针失败：', e.message);
  process.exit(1);
});
