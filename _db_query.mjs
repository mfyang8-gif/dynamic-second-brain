// 通用只读 SQL 探针（Phase 4 排查用）。
// 用法：node _db_query.mjs "SELECT TABLE_NAME, TABLE_COLLATION FROM information_schema.TABLES WHERE ..."
//
// ⚠️ 两个已踩过的坑（不要"优化"掉）：
//  ① Docker Engine API 的 /exec/{id}/start 返回的是【多路复用二进制流】（8 字节帧头：
//     streamType + 3 保留 + 4 字节大端长度），不是 JSON —— 直接 res.json() 会报
//     `Unexpected token`。必须读 arrayBuffer 再 demux。
//  ② mysql 客户端必须显式传库名（Cmd 数组末尾），否则报 ERROR 1046 No database selected。
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

const apiRaw = async (path, init) => {
  const res = await fetch(`${DOCKER}${path}`, {
    ...init,
    headers: { 'Content-Type': 'application/json', ...(init?.headers || {}) },
  });
  const buf = Buffer.from(await res.arrayBuffer());
  if (!res.ok) throw new Error(`${res.status} ${buf.toString('utf8')}`);
  return buf;
};

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

const runSql = async (sql) => {
  const created = await api(`/containers/${encodeURIComponent(CONTAINER)}/exec`, {
    method: 'POST',
    body: JSON.stringify({
      AttachStdout: true,
      AttachStderr: true,
      Cmd: ['mysql', `-u${USER}`, `-p${PASS}`, '--default-character-set=utf8mb4', '-e', sql, DB],
    }),
  });
  if (!created?.Id) throw new Error('exec 创建失败');
  const raw = await apiRaw(`/exec/${created.Id}/start`, {
    method: 'POST',
    body: JSON.stringify({ Detach: false, Tty: false }),
  });
  return demux(raw).replace(/\r/g, '');
};

const main = async () => {
  const sql = process.argv.slice(2).join(' ');
  if (!sql) {
    console.error('用法：node _db_query.mjs "SELECT ..."');
    process.exit(2);
  }
  const out = await runSql(sql);
  const clean = out
    .split('\n')
    .filter((l) => !l.includes('Using a password on the command line'))
    .join('\n')
    .trim();
  console.log(clean || '(空结果)');
};

main().catch((e) => {
  console.error('查询失败：', e.message);
  process.exit(1);
});
