// 执行 B1 数据面 DDL（Phase 4 · B1）
// 只做两件事：ALTER chat_message 加 4 列 + 2 索引；CREATE TABLE graphiti_write_audit。
// 纯增量、可回滚（DROP COLUMN / DROP TABLE 即可回到原状），不改任何既有数据。
// 用法：node _db_apply_b1_ddl.mjs
const DOCKER = process.env.DOCKER_HOST_API || 'http://192.168.88.130:2375';
const CONTAINER = process.env.MYSQL_CONTAINER || 'mysql';
const DB = process.env.MYSQL_DB || 'dynamic_brain';
const USER = process.env.MYSQL_USER || 'root';
const PASS = process.env.MYSQL_PASS || 'root';
const SQL_FILE = new URL('./src/main/resources/db/phase4-b1-graphiti-write-chain.sql', import.meta.url);

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
  return { out: demux(raw).replace(/\r/g, ''), exit: created.ExitCode };
};

const main = async () => {
  const fs = await import('node:fs/promises');
  const raw = await fs.readFile(SQL_FILE, 'utf8');

  // 剥掉纯注释行（以 -- 开头），保留语句；避免把运维提示段落当 SQL 发给服务端
  const statements = raw
    .split('\n')
    .filter((line) => !line.trim().startsWith('--'))
    .join('\n')
    .split(';')
    .map((s) => s.trim())
    .filter((s) => s.length > 0);

  console.log(`# 目标 ${DB}@${CONTAINER}  待执行语句 ${statements.length} 条\n`);
  for (const stmt of statements) {
    const head = stmt.replace(/\s+/g, ' ').slice(0, 90);
    console.log(`--- 执行：${head}...`);
    const { out } = await runSql(stmt + ';');
    const clean = out
      .split('\n')
      .filter((l) => !l.includes('Using a password on the command line'))
      .join('\n')
      .trim();
    if (clean) {
      console.log(clean);
      if (/Duplicate column|Duplicate key name/i.test(clean)) {
        console.log('   （已应用过，属预期幂等冲突）');
      }
    } else {
      console.log('   OK');
    }
  }
  console.log('\n完成。请用 _db_schema_probe.mjs 复核。');
};

main().catch((e) => {
  console.error('执行失败：', e.message);
  process.exit(1);
});
