// RabbitMQ 优先级语义取证（Phase 4 · B1 附录 D #9 + #11）
//
// #11（★「必须实测、不能假设」项）：自己构造 Message 进 retry.* 队列后，
//     经 broker 的 TTL+DLX 回到 write.queue 时，priority 属性是否还在？
//     丢失 → 高危消息失败一次即降级为平民，补丁 6 形同虚设，必须改手动 republish。
// #9 ：x-max-priority 的插队是否真的生效（先压 N 条 priority=1，再投 1 条 priority=10）。
//
// ⚠️ 前提：write.queue 上【没有消费者】。若应用正在跑（消费者在线），消息会被立刻拿走，
//    本探针会明确报错而不是给出假结论。
// ⚠️ 副作用：会向真实队列投测试消息，结束时全部 ack 清除，并复核队列为空。
// 用法：MQ_USER=root MQ_PASS=root node _mq_priority_probe.mjs
const MQ_API = process.env.MQ_API || 'http://192.168.88.130:15672';
const USER = process.env.MQ_USER || 'guest';
const PASS = process.env.MQ_PASS || 'guest';
const WRITE_Q = 'graphiti.write.queue';
const RETRY_30S_Q = 'graphiti.write.retry.30s.queue';
const auth = 'Basic ' + Buffer.from(`${USER}:${PASS}`).toString('base64');

const req = async (path, init) => {
  const res = await fetch(`${MQ_API}${path}`, {
    ...init,
    headers: { Authorization: auth, 'Content-Type': 'application/json', ...(init?.headers || {}) },
  });
  const text = await res.text();
  if (!res.ok) throw new Error(`${path} → ${res.status} ${text.slice(0, 300)}`);
  return text ? JSON.parse(text) : null;
};

const queueInfo = (q) => req(`/api/queues/%2F/${encodeURIComponent(q)}`);

const publish = (routingKey, props) =>
  req('/api/exchanges/%2F/amq.default/publish', {
    method: 'POST',
    body: JSON.stringify({
      properties: props,
      routing_key: routingKey,
      payload: JSON.stringify({ probe: true, ts: new Date().toISOString() }),
      payload_encoding: 'string',
    }),
  });

/** 从队列里取消息（ack_requeue_false = 取走并 ack，用于观察出队顺序）。 */
const drain = (q, count) =>
  req(`/api/queues/%2F/${encodeURIComponent(q)}/get`, {
    method: 'POST',
    body: JSON.stringify({ count, ackmode: 'ack_requeue_false', encoding: 'auto' }),
  });

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const assertNoConsumer = async (q) => {
  const info = await queueInfo(q);
  if ((info.consumers ?? 0) > 0) {
    throw new Error(`${q} 上已有消费者（consumers=${info.consumers}）：结论会失真，请先停掉应用再跑本探针`);
  }
  return info;
};

const main = async () => {
  console.log(`# broker=${MQ_API}\n`);
  const before = await assertNoConsumer(WRITE_Q);
  if ((before.messages_ready ?? 0) > 0) {
    console.log(`⚠️ ${WRITE_Q} 已有 ${before.messages_ready} 条积压，为免混淆先清空`);
    await drain(WRITE_Q, before.messages_ready);
  }
  console.log(`✓ ${WRITE_Q} 无消费者、已清空\n`);

  // ---------------- #11：TTL+DLX 是否保留 priority ----------------
  console.log('===== #11 retry.30s → write.queue 经 broker DLX 后 priority 是否保留 =====');
  await publish(RETRY_30S_Q, { priority: 10, delivery_mode: 2 });
  const inRetry = await queueInfo(RETRY_30S_Q);
  console.log(`已投 priority=10 到 ${RETRY_30S_Q}（ready=${inRetry.messages_ready}），TTL 30s，等待死信…`);
  await sleep(36_000);

  const writeInfo = await queueInfo(WRITE_Q);
  console.log(`TTL 到期后 ${WRITE_Q} ready=${writeInfo.messages_ready}`);
  const got = await drain(WRITE_Q, 1);
  if (!got.length) {
    console.log('✗ 消息没有回到 write.queue —— DLX 路由配置可能有问题（检查 x-dead-letter-routing-key=write 与绑定）');
  } else {
    const p = got[0].properties?.priority;
    console.log(`回队消息 properties.priority = ${JSON.stringify(p)}`);
    console.log(
      p === 10
        ? '✓ 结论：broker TTL+DLX 保留 priority —— 重试链路无需手动 republish'
        : `✗ 结论：priority 丢失（实际 ${JSON.stringify(p)}）→ 必须把 retry→write 那一跳改成手动 republish 并显式 setPriority`
    );
  }

  // ---------------- #9：优先级插队 ----------------
  console.log('\n===== #9 x-max-priority 插队是否生效 =====');
  for (let i = 0; i < 10; i++) await publish(WRITE_Q, { priority: 1, delivery_mode: 2 });
  await publish(WRITE_Q, { priority: 10, delivery_mode: 2 });
  const pushed = await queueInfo(WRITE_Q);
  console.log(`已压 10 条 priority=1 + 1 条 priority=10（最后投），队列 ready=${pushed.messages_ready}`);

  const popped = await drain(WRITE_Q, 11);
  const order = popped.map((m) => m.properties?.priority);
  console.log(`出队顺序的 priority = [${order.join(', ')}]`);
  console.log(
    order[0] === 10 && order.slice(1).every((x) => x === 1)
      ? '✓ 结论：priority=10 插到队头 —— 补丁 6 生效（注意：仅队列积压时有意义）'
      : '✗ 结论：插队未生效，需复查 x-max-priority 声明'
  );

  // ---------------- 收尾 ----------------
  const after = await queueInfo(WRITE_Q);
  const left = after.messages_ready ?? 0;
  if (left > 0) {
    await drain(WRITE_Q, left);
  }
  const finalInfo = await queueInfo(WRITE_Q);
  console.log(`\n收尾：${WRITE_Q} ready=${finalInfo.messages_ready}（应为 0）、consumers=${finalInfo.consumers}`);
};

main().catch((e) => {
  console.error('探针失败：', e.message);
  process.exit(1);
});
