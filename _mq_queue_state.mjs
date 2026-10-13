// RabbitMQ 队列真实状态探针（Phase 4 · B1 附录 D / 上线后 DLQ 监控）
//
// ★ 为什么不直接读 messages_ready：它是 broker 每 5s 一次的快照统计
//   （collect_statistics_interval 默认 5000ms），刚投刚取完时必然滞后。
//   「队列里到底还有几条」必须用一次真实 get（ack_requeue_true，不改动队列）来量。
//
// 用法：MQ_USER=root MQ_PASS=root node _mq_queue_state.mjs
const MQ_API = process.env.MQ_API || 'http://192.168.88.130:15672';
const USER = process.env.MQ_USER || 'guest';
const PASS = process.env.MQ_PASS || 'guest';
const auth = 'Basic ' + Buffer.from(`${USER}:${PASS}`).toString('base64');

const QUEUES = [
  'graphiti.scan.delay.queue',
  'graphiti.scan.queue',
  'graphiti.scan.dlq',
  'graphiti.write.queue',
  'graphiti.write.retry.30s.queue',
  'graphiti.write.retry.2min.queue',
  'graphiti.write.retry.10min.queue',
  'graphiti.write.dlq',
  'semantic.scan.delay.queue',
  'semantic.scan.queue',
];

const req = async (path, init) => {
  const res = await fetch(`${MQ_API}${path}`, {
    ...init,
    headers: { Authorization: auth, 'Content-Type': 'application/json', ...(init?.headers || {}) },
  });
  const text = await res.text();
  if (!res.ok) throw new Error(`${path} → ${res.status} ${text.slice(0, 200)}`);
  return text ? JSON.parse(text) : null;
};

/** 真实取数（只读：ack_requeue_true 会把消息放回）。 */
const realPeek = async (q, count = 100) => {
  try {
    const res = await req(`/api/queues/%2F/${encodeURIComponent(q)}/get`, {
      method: 'POST',
      body: JSON.stringify({ count, ackmode: 'ack_requeue_true', encoding: 'auto' }),
    });
    return res.length;
  } catch (e) {
    return `ERR:${e.message.slice(0, 60)}`;
  }
};

const main = async () => {
  console.log(`# broker=${MQ_API}`);
  console.log('# 列「真实条数」= 一次 get 实际返回的条数；「统计」= messages_ready 快照（可能滞后 5s）\n');
  console.log('队列'.padEnd(34) + '真实条数'.padEnd(10) + '统计'.padEnd(8) + '消费者');
  let anyNonEmpty = false;
  for (const q of QUEUES) {
    let info;
    try {
      info = await req(`/api/queues/%2F/${encodeURIComponent(q)}`);
    } catch {
      console.log(`${q.padEnd(34)}(不存在)`);
      continue;
    }
    const real = await realPeek(q);
    if (typeof real === 'number' && real > 0) anyNonEmpty = true;
    console.log(
      `${q.padEnd(34)}${String(real).padEnd(10)}${String(info.messages_ready ?? 0).padEnd(8)}${info.consumers ?? 0}`
    );
  }
  console.log(anyNonEmpty ? '\n⚠️ 有队列非空（上面「真实条数」> 0 的行）' : '\n✓ 所有队列真实为空');
};

main().catch((e) => {
  console.error('探针失败：', e.message);
  process.exit(1);
});
