// RabbitMQ 拓扑取证探针（Phase 4 · B1 附录 D）
// 用途：从 broker 侧读回【真实声明出来】的队列参数，验证：
//   ① graphiti.* 全部队列存在；② write.queue 的 x-max-priority = 10；
//   ③ 每个 TTL/重试队列的 x-message-ttl 与 DLX 路由；④ scan.queue 的 DLX（隐患修复）；
//   ⑤ 旧链 semantic.scan.queue 是否被连带改动（应为「无 DLX」，见代码注释）。
// 只读：仅调用 /api/queues 与 /api/exchanges，不做任何 declare/delete。
// 用法：node _mq_topology_probe.mjs
//       MQ_API=http://host:15672 MQ_USER=guest MQ_PASS=guest node _mq_topology_probe.mjs
const MQ_API = process.env.MQ_API || 'http://192.168.88.130:15672';
const USER = process.env.MQ_USER || 'guest';
const PASS = process.env.MQ_PASS || 'guest';

const auth = 'Basic ' + Buffer.from(`${USER}:${PASS}`).toString('base64');

const get = async (path) => {
  const res = await fetch(`${MQ_API}${path}`, { headers: { Authorization: auth } });
  const text = await res.text();
  if (!res.ok) throw new Error(`${path} → ${res.status} ${text.slice(0, 200)}`);
  return JSON.parse(text);
};

const argsOf = (obj, keys) => {
  const out = {};
  for (const k of keys) {
    const v = obj.arguments?.[k];
    if (v !== undefined) out[k] = v;
  }
  return out;
};

const main = async () => {
  const queues = await get('/api/queues');
  const byName = new Map(queues.map((q) => [q.name, q]));

  const expected = [
    'graphiti.scan.delay.queue',
    'graphiti.scan.queue',
    'graphiti.scan.dlq',
    'graphiti.write.queue',
    'graphiti.write.dlq',
    'graphiti.write.retry.30s.queue',
    'graphiti.write.retry.2min.queue',
    'graphiti.write.retry.10min.queue',
    'semantic.scan.delay.queue',
    'semantic.scan.queue',
  ];

  console.log(`# broker=${MQ_API} 队列总数=${queues.length}\n`);
  console.log('## 逐队列参数');
  for (const name of expected) {
    const q = byName.get(name);
    if (!q) {
      console.log(`✗ ${name.padEnd(34)} 不存在`);
      continue;
    }
    const interesting = argsOf(q, [
      'x-max-priority',
      'x-message-ttl',
      'x-dead-letter-exchange',
      'x-dead-letter-routing-key',
    ]);
    const consumers = q.consumers ?? 0;
    console.log(
      `✓ ${name.padEnd(34)} durable=${q.durable} ready=${q.messages_ready ?? 0} ` +
        `unacked=${q.messages_unacknowledged ?? 0} consumers=${consumers} args=${JSON.stringify(interesting)}`
    );
  }

  console.log('\n## 关键断言');
  const write = byName.get('graphiti.write.queue');
  console.log(
    write?.arguments?.['x-max-priority'] === 10
      ? '✓ write.queue 是优先级队列（x-max-priority=10）'
      : `✗ write.queue 的 x-max-priority = ${write?.arguments?.['x-max-priority']}（期望 10）`
  );

  const scan = byName.get('graphiti.scan.queue');
  console.log(
    scan?.arguments?.['x-dead-letter-exchange'] === 'graphiti.scan.dlx'
      ? '✓ scan.queue 已配 DLX（旧链隐患 2 的结构性修复）'
      : `✗ scan.queue 缺 DLX：${JSON.stringify(scan?.arguments)}`
  );

  const legacy = byName.get('semantic.scan.queue');
  console.log(
    legacy && !legacy.arguments?.['x-dead-letter-exchange']
      ? '✓ 旧链 semantic.scan.queue 未被改动（其消费者 catch-all 吞异常，无消息会死信，加 DLX 是死代码）'
      : `· semantic.scan.queue 参数：${JSON.stringify(legacy?.arguments ?? null)}`
  );

  const retryTtls = ['graphiti.write.retry.30s.queue', 'graphiti.write.retry.2min.queue', 'graphiti.write.retry.10min.queue']
    .map((n) => byName.get(n)?.arguments?.['x-message-ttl'])
    .join('/');
  console.log(retryTtls === '30000/120000/600000'
    ? '✓ 三档重试队列 TTL = 30000/120000/600000（三个静态队列，无队头阻塞）'
    : `✗ 重试 TTL = ${retryTtls}（期望 30000/120000/600000）`);

  const consumerless = expected
    .map((n) => byName.get(n))
    .filter((q) => q && (q.consumers ?? 0) === 0 && !q.name.startsWith('graphiti.write.retry'))
    .map((q) => q.name);
  console.log(`· 当前无消费者的队列：${consumerless.join(', ') || '(无)'}`);

  console.log('\n## 相关交换机');
  const exchanges = await get('/api/exchanges');
  for (const name of ['graphiti.scan.dlx', 'graphiti.write.exchange']) {
    const e = exchanges.find((x) => x.name === name);
    console.log(e ? `✓ ${name} type=${e.type} durable=${e.durable}` : `✗ ${name} 不存在`);
  }
};

main().catch((e) => {
  console.error('探针失败：', e.message);
  process.exit(1);
});
