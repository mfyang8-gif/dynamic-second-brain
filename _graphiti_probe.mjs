// Graphiti 契约实测探针（Phase 4 · B1 附录 D #8 / #6 / #2b 回归）
//   #8  ★ /episodes?last_n=200 的响应时间与体积（决定能否用固定大值替代动态 last_n）
//   #6  RedisChatMemoryStore 的默认 TTL（若很短，L1 记忆会在会话中途静默丢失）
//   #2b /episodes 的排序键回归（应为 created_at 而非 valid_at，设计 §3.6.1 公式依赖它）
// 只读：只做 GET，不改任何图谱数据。
// 用法：GRAPHITI=http://192.168.88.130:8088 node _graphiti_probe.mjs
const GRAPHITI = process.env.GRAPHITI || 'http://192.168.88.130:8088';
const DOCKER = process.env.DOCKER_HOST_API || 'http://192.168.88.130:2375';
const REDIS_CONTAINER = process.env.REDIS_CONTAINER || 'redis';

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const timed = async (label, url) => {
  const t0 = Date.now();
  const res = await fetch(url);
  const text = await res.text();
  const ms = Date.now() - t0;
  console.log(`  ${label.padEnd(26)} status=${res.status} 耗时=${String(ms).padStart(5)}ms 体积=${text.length} 字节`);
  return { ms, size: text.length, status: res.status, text };
};

// ---------- Docker exec（用于 redis-cli） ----------
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
  return Buffer.from(await res.arrayBuffer());
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

const dockerExec = async (container, cmd) => {
  const created = await api(`/containers/${encodeURIComponent(container)}/exec`, {
    method: 'POST',
    body: JSON.stringify({ AttachStdout: true, AttachStderr: true, Cmd: cmd }),
  });
  const raw = await apiRaw(`/exec/${created.Id}/start`, {
    method: 'POST',
    body: JSON.stringify({ Detach: false, Tty: false }),
  });
  return demux(raw).replace(/\r/g, '');
};

const main = async () => {
  // ============ #8 ============
  console.log(`===== #8 /episodes?last_n=200 的响应时间与体积（${GRAPHITI}）=====`);
  const probe = await timed('GET /healthcheck', `${GRAPHITI}/healthcheck`).catch(() => null);
  if (!probe || probe.status !== 200) {
    console.log('  ✗ Graphiti 不可达，跳过 #8');
  } else {
    // 找一个真实有 episode 的 group：先看 /episodes 在不存在的组上的行为
    for (const g of ['user_3', 'user_1', 'user_999999']) {
      const small = await timed(`last_n=20  (${g})`, `${GRAPHITI}/episodes?group_id=${g}&last_n=20`);
      const big = await timed(`last_n=200 (${g})`, `${GRAPHITI}/episodes?group_id=${g}&last_n=200`);
      let arr = [];
      try {
        arr = JSON.parse(big.text);
      } catch {
        /* ignore */
      }
      const n = Array.isArray(arr) ? arr.length : 0;
      if (n > 0) {
        console.log(`  → ${g} 有 ${n} 条 episode；体积放大比 ${(big.size / Math.max(1, small.size)).toFixed(2)}×`);
        console.log('  → 判据（设计 §3.6.1 备选）：若「固定 last_n=200」的耗时与体积可接受，可退化固定大值');
        // #2b 排序键回归：created_at 是否单调不增
        const created = arr.map((e) => e.created_at);
        const sorted = [...created].sort().reverse();
        console.log(
          JSON.stringify(created) === JSON.stringify(sorted)
            ? '  ✓ 排序键回归：/episodes 仍按 created_at 倒序（动态 last_n 公式的前提成立）'
            : '  ✗ 排序键已变！§3.6.1 的 last_n 公式可能静默失效（附录 D #2b）'
        );
        // 顺带确认 name 锚点可读回
        const withName = arr.filter((e) => typeof e.name === 'string' && e.name.startsWith('cm_')).length;
        console.log(`  · 带 cm_ 前缀 name 锚点的 episode：${withName} 条（确认锚点机制可用）`);
        break;
      }
      console.log(`  → ${g} 无 episode（返回体长度 ${big.size}）\n`);
    }
  }

  // ============ #6 ============
  console.log('\n===== #6 RedisChatMemoryStore 默认 TTL =====');
  try {
    const containers = await api('/containers/json');
    const names = containers.map((c) => c.Names.join('').replace(/^\//, ''));
    console.log(`  Docker 容器：${names.join(', ')}`);
    const redisName = names.find((n) => /redis/i.test(n)) || REDIS_CONTAINER;
    const ping = await dockerExec(redisName, ['redis-cli', 'ping']);
    console.log(`  redis-cli ping (${redisName}) → ${ping.trim()}`);
    const keys = await dockerExec(redisName, ['redis-cli', '--scan', '--count', '200']);
    const list = keys.split('\n').filter((k) => k.trim()).slice(0, 30);
    console.log(`  采样 ${list.length} 个 key：`);
    for (const k of list) {
      const ttl = (await dockerExec(redisName, ['redis-cli', 'ttl', k])).trim();
      const shown = k.length > 60 ? k.slice(0, 60) + '…' : k;
      console.log(`    TTL=${ttl.padStart(8)}  ${shown}`);
    }
    if (!list.length) console.log('    (库 0 为空 —— 无会话记忆缓存，或应用未运行过带记忆的会话)');
    console.log('  ⚠️ TTL=-1 表示永不过期；TTL=-2 表示 key 不存在。判据：若记忆 key 的 TTL 存在且 < 30min，需显式设置。');
  } catch (e) {
    console.log(`  ✗ Redis 探测失败：${e.message}`);
  }

  await sleep(50);
};

main().catch((e) => {
  console.error('探针失败：', e.message);
  process.exit(1);
});
