# Phase 4 · Graphiti 记忆微服务集成 — 后端设计方案 v2

> **架构已拍板**：采用 Mem0 三层记忆思想，Graphiti 全面接管「语义与偏好记忆」层，**彻底废弃 Phase 3 的 MySQL 语义落库方案（不搞双轨制，无历史包袱）**。
> 本文档为**实施方案**，在我方与架构师 Review 拍板之前**不含任何业务代码**。
> v2 取代 v1 的 §3.1（原「Q1 待拍板」现已裁决为 B：Graphiti 上位替换）。

---

## 一、架构决断（已拍板，作为后续一切设计的最高准则）

### 1.1 三层记忆 × 物理映射（铁律）

| 层 | 回答什么 | 物理载体 | 现有代码锚点 | 写入方 | 读取方 |
|---|---|---|---|---|---|
| **L1 短期工作记忆** | 「我刚才那句话是什么意思」「把上一段代码重写」 | Redis（`MessageWindowChatMemory` + `RedisChatMemoryStore`）+ MySQL `chat_message` 原文 | `ChatMemoryProviderConfig`、`buildConversationContext()`、`ChatSummaryService` | 每轮对话落库 | `{{historySummary}}` + `{{recentContext}}` |
| **L2 语义与偏好记忆** | 「我老婆对什么过敏」（精确关系）、「我写代码有什么习惯」（模糊画像） | **Graphiti (Neo4j) — 唯一真相源** | 本 Phase 新建 | MQ `graphiti.write.*` | `{{userGlobalMemory}}` + `{{episodicMemory}}` |
| **L3 情景流水账与知识库** | 「我昨天原话怎么怼的」「根据我上传的 PDF 总结」 | Qdrant（纯向量，不做任何逻辑裁决） | `dynamic_brain` 集合、`QdrantTemplate`、`Bm25SparseEncoder` | 文档上传时切片 | `{{context}}` |

**为什么 L2 必须是 Graphiti 而不是「Qdrant 也能做向量检索」**：Qdrant 能做相似度，但**没有时间维度**。凡是会随时间改变的主观属性（习惯、偏好、人设），只有 Graphiti 的 `invalid_at` / `expired_at` 双时间戳能做**裁决**——把旧事实作废、新事实生效，且作废时刻与新事实生效时刻毫秒级一致（已实测复现）。缺了它，新旧偏好会并存，模型必然自相矛盾。

### 1.2 三条禁令

1. **严禁**把任何提炼后的习惯/偏好写入 Qdrant 或 MySQL —— 会丧失时效裁决能力，导致主模型「精神分裂」。
2. **严禁**在 L2 之外存在第二份「用户偏好真相」—— 包括把旧 `user_semantic_memory` 表留作只读并行。（这就是「不搞双轨」的物理含义：不是不建表，而是**不留第二个权威源**。）
3. **严禁** Qdrant 承担任何逻辑裁决 —— 它只负责返回「发生了就永不改变的文本切片」，不带时效语义、不做对错判断。

### 1.3 ⚠️ 与代码现状的两处偏差（必须先对齐，否则方案会落空）

**偏差 A：Qdrant 里从来没有「对话流水账」。**
`spring.data.qdrant.collections` 只有两个集合（实读 `application.yaml`）：

- `dynamic_brain` —— 用户**上传文档**的切片（indexes: `bookId` / `userId` / `pageId` / `createdAt`）
- `user_semantic_habits` —— 习惯语义查重索引（**本次废弃对象**）

也就是说：用户举的那个例子（一个月前那句「我最爱的语言是 Java」进了 Qdrant 流水账）**在当前代码里不成立** —— 那句话实际存在于 MySQL `chat_message`（对话原文）与曾经的 `user_semantic_memory`（提炼结果）。风险一**依然真实且更严重**，但冲突双方必须重新定位，见 §4.1。

**偏差 B：`user_semantic_habits` 这个集合本身违反铁律 1** —— 它按定义就是「习惯的向量副本」。必须随 Phase 3 一并删除，否则"两条真相"会以你没想到的形态残留。

---

## 二、第一部分 · Graphiti Client 封装与 DTO 设计

### 2.1 HTTP 客户端选型：`RestClient`（附两条硬约束）

**选型理由**：
- 调用点**全是阻塞式**（MQ 消费线程 + 流式管线的虚拟线程），WebClient 无任何优势，只会逼出 `.block()`；
- 工程**无任何 Spring Cloud 依赖**，引 OpenFeign 要引整条版本线，且其自带重试器会与本项目的 MQ 重试机制**叠乘**；
- `RestClient` **零新增依赖**（spring-web 已在），是 Spring Boot 3.4 官方推荐。

**两条硬约束（违反即线上事故）**：

| # | 约束 | 原因 |
|---|---|---|
| 1 | **绝不复用现有 `restTemplate` Bean** | `RestTemplateConfig` 的 read timeout 是 **60s**，会钉死 MQ 消费线程与 SSE 首字。Graphiti client 必须自建 `RestClient` |
| 2 | **绝不共用全局 `ObjectMapper`** | `JacksonObjectMapper` 被注册为 `@Primary`，把 `LocalDateTime` 钉死为 `yyyy-MM-dd'T'HH:mm:ss`；而 Graphiti 返回的是 `2026-10-12T09:51:56.345970+00:00`（微秒 + UTC offset，来自 `FactResult.Config.json_encoders`）→ **解析必然抛异常**。client 自建 ObjectMapper（`JavaTimeModule` + `OffsetDateTime`），与 Web 层彻底解耦 |

### 2.2 DTO —— 逐字段对照真实 schema（容器源码实读，非推测）

**写入侧** `POST /messages` ← `AddMessagesRequest {group_id, messages[]}`

```java
record GraphitiWriteRequest(String groupId, List<GraphitiMessage> messages) {}

record GraphitiMessage(
        String content,
        String uuid,              // 可选，我们用来承载确定性 ID
        String name,              // 默认 ""
        String roleType,          // Literal: user / assistant / system（必填）
        String role,              // ⚠️ 必填字段（可为 null，但必须出现）
        OffsetDateTime timestamp, // ⚠️ 必须显式传，否则被盖上"处理时刻"
        String sourceDescription  // 默认 ""
) {}
```

**四个字段级陷阱（每个都读源码确认过）**

| 陷阱 | 后果 | 规避 |
|---|---|---|
| `messages[]` 漏包一层 | 422 `{"loc":["body","messages"],"msg":"Field required"}` | 唯一入口构造器强制包装 |
| `role` 不传 | ⚠️ **422** —— 源码是 `role: str \| None = Field(description=...)`，**没有 default**，pydantic 视作必填（可为 null 但字段必须出现） | DTO **不加** `@JsonInclude(NON_NULL)`，显式传 `"user"` |
| `role_type` 取值越界 | 422 —— 源码是 `Literal['user','assistant','system']` | 用 Java 枚举，编译期约束 |
| `timestamp` 不传 | 源码 `default_factory=utc_now` → **历史对话会被盖上"处理时刻"的时间戳**，双时间戳裁决直接失真 | **必须显式传 `chat_message.created_at`**（转 UTC `OffsetDateTime`） |

**检索侧** `POST /search` ← `SearchQuery {group_ids[], query, max_facts=10}` → `SearchResults {facts[]}`

```java
record GraphitiSearchRequest(List<String> groupIds, String query, Integer maxFacts) {}

record GraphitiFact(
        String uuid, String name, String fact,
        OffsetDateTime validAt,       // 可空
        OffsetDateTime invalidAt,     // 可空 ← 作废标记
        OffsetDateTime createdAt,     // ★ 非空必填
        OffsetDateTime expiredAt,     // 可空
        String sourceNodeUuid, String targetNodeUuid,
        List<String> episodes         // 默认 []
) {}
```

**三个必须知道的字段事实（读 `dto/retrieve.py` 原文）**

1. `group_ids` 是 `list[str] | None = None` —— **可选且默认无过滤**。漏传，或写成单数 `group_id`（字段被静默丢弃）→ **跨租户全局检索且 HTTP 200**，零报错。→ Client 内部**强制断言非空**，不靠调用方自觉。
2. `FactResult` **没有 `group_id`** —— 客户端无法自证一条 fact 属于谁。→ DTO 里干脆不设该字段（避免误导），隔离责任 100% 落在 `group_ids` 上。
3. `created_at` 是**非空必填**，其余三个时间字段可空 → 全部用 `OffsetDateTime`。

### 2.3 Client 接口：唯一出口 + 语义化旁路

```java
public interface GraphitiMemoryClient {

    /** 唯一召回出口：内部已完成 ①groupIds 非空断言 ②invalidAt 过滤 ③消毒 */
    List<RecalledFact> recallActive(String userId, String query, int maxFacts);

    /** 显式命名的旁路：排障 / 审计 / 「我的记忆」页面需要看已作废事实 */
    List<RecalledFact> recallIncludingInvalidated(String userId, String query, int maxFacts);

    GraphitiWriteAck write(String userId, List<GraphitiMessage> messages);

    List<EpisodeRef> listEpisodes(String userId, int lastN);

    void deleteEdge(String edgeUuid);
    String healthcheck();
}

/** ★ 结构上就没有 invalidAt 字段 —— 类型系统即防呆 */
record RecalledFact(String factId, String statement, OffsetDateTime effectiveAt) {}
```

关键设计：`RecalledFact` **在类型上不可能携带作废事实**。过滤发生在「唯一出口」内部，而不是靠每个调用点自觉；想拿作废事实必须**显式调用另一个方法名**——「要打错字才会犯错」就是防呆本身。

### 2.4 过滤放在哪一层：**Client/Service 层，不是拦截器层**

| 方案 | 判定 |
|---|---|
| HTTP 拦截器里过滤 | ❌ **会产生信息黑洞**：排障要看已作废边、前端「记忆变更历史」要看、双时间戳验收本身要读作废事实 —— 三条路全断。且技术上需自己 `readTree → 改 JSON → 重序列化`，等于解析两遍 |
| Client 的 `recallActive()` 内部（≈ Service 层语义） | ✅ **推荐**：既收敛了默认行为，又不堵死旁路 |

### 2.5 超时分档（读操作必须远短于写操作）

| 调用 | connect | read | 依据 |
|---|---|---|---|
| 检索（在线路径，阻塞 SSE 首字） | 1s | **2s** | 有硬预算，超时即降级为「无记忆」 |
| 写入（MQ 路径，不阻塞用户） | 2s | **5s** | 只要 202 到手即可 |
| 落库确认轮询 | 1s | 3s | 短，可多次 |

---

## 三、第二部分 · 基于 RabbitMQ 的异步写入设计

### 3.1 现有链路逐行锚点 + 三个隐患

**现有链路（骨架极好，直接复用）**：
`ChatStreamService:1110 armSessionScan()` →（落库成功后）Redis `setnx semantic:scan:armed:{sessionId}` TTL 5min（**冷却锁**：把「N 轮对话 N 次 LLM 分析」收敛为「会话静默 5 分钟后仅扫一次」）→ 投 `semantic.scan.delay.queue`（队列级 TTL 300_000）→ 过期死信到 `semantic.scan.exchange` → `semantic.scan.queue` → `SemanticScanConsumer.onScanMessage()` → 取最近 10 条 user 轮次 → `HabitExtractor` → `SemanticMemoryServiceImpl.processAndPromote()`。

**但三个隐患必须先修**（否则新链路会把它们放大成静默丢数据）：

| # | 隐患 | 后果 |
|---|---|---|
| 1 | `semantic.scan.queue` **未声明 DLX**，且全局 `default-requeue-rejected: false` | 消费抛异常 = **静默丢弃且不重投**，队列看起来"正常" |
| 2 | Consumer 是 catch-all 吞异常 | **完全没重试** |
| 3 | `armed` 锁在 `finally` 里**无条件** `releaseArmLock()` | 本轮消费失败时，丢失的那一轮不会被补扫（注释写的"下次会话自然重扫"只在用户继续说话时成立） |

### 3.2 新链路拓扑

```
落库成功 ──► armSessionScan()          [冷却锁语义不变，仍为 5min setnx]
                 │
                 ▼  默认交换机("")，routingKey = 队列名
     graphiti.write.delay.queue        队列级 TTL 300s, DLX = graphiti.write.dlx
                 │ 过期
                 ▼
     graphiti.write.dlx ──[rk=write]──► graphiti.write.queue
                                            │  concurrency = 1 / prefetch = 1
                                            ▼
                                    GraphitiWriteConsumer
                                            │
        ┌───────────────────┬───────────────┴──────────────┐
        ▼                   ▼                              ▼
    成功 ack          可重试失败                       毒消息 (400/422)
                   → retry.30s.queue                       │
                      │过期(DLX 回 write.queue)             ▼
                      ├→ retry.2min.queue            graphiti.write.dlq
                      └→ retry.10min.queue
```

- **重试队列必须是 3 个「静态 TTL」队列**：RabbitMQ 队列 TTL 是**队列级**属性，队首消息的 TTL 会挡住后面的短 TTL 消息（**队头阻塞**）。单队列 + 多档 TTL 是错的。
- **`concurrency=1 / prefetch=1` 不是保守，是必须**：Graphiti 侧 `AsyncWorker` 是**单协程串行**消费 `asyncio.Queue`，压再多并发只是把排队搬到服务端，而我们这边反而多占线程 + 多占确认轮询带宽。
- ⚠️ **新消息类必须登记进 `RabbitSemanticConfig.jacksonMessageConverter()` 的 `addTrustedPackages`** —— 白名单是**包名精确 `equals`**（源码注释里已有血泪记录：只写父包不生效，表现为「队列正常清空、deliver+1 但 ack 恒为 0、消费者方法体从不执行」）。若新类放在 `com.yangmf.mini_nodepad.mq` 则天然已覆盖。

### 3.3 消费者流水线（六步，含风险三的防御）

```
① 幂等门    Redis setnx(graphiti:write:dedup:{msgId}, TTL 1h)     [> 30s+2min+10min 最大重试窗]
② 取窗口    按 sessionId 取 [水位, now] 之间的 user 轮次（MySQL chat_message；
            @TableLogic 自动排除 regenerate 软删；★ 只取 user —— 沿用既有裁决：
            「AI 回答中的客串假设句会诱导抽取器产生自我强化幻觉」）
③ 实质判定   ★风险三  逐轮过 MemorySignalJudge（二值），只保留"含用户属性/事实"的轮次
④ 投递      一轮 = 一条 message；uuid = "cm_" + chatMessageId；timestamp = created_at
⑤ 落库确认  轮询 GET /episodes/{groupId}?last_n=N，按 uuid 比对（见 3.6）
⑥ 水位推进  Redis SET graphiti:scan:watermark:{sessionId} = 本轮最大 created_at
```

**第 ⑥ 步是新增的结构性修复**：有了水位，即使某一轮漏扫，下一轮也能从水位继续 ——而不是只靠「用户恰好又说话了」。这是对隐患 3 的根治。

### 3.4 ★ 关键发现：把 `messages[]` 理解成「一个 episode」是错的

读容器内**真实源码**（`/app/graph_service/routers/ingest.py`）得到决定性事实：

```python
async def add_messages_task(m: Message):
    await graphiti.add_episode(
        uuid=m.uuid,                         # ← 由客户端指定
        group_id=request.group_id,
        name=m.name,
        episode_body=f'{m.role or ""}({m.role_type}): {m.content}',
        reference_time=m.timestamp,          # ← 每条消息各自的 reference_time
        source=EpisodeType.message,
        source_description=m.source_description,
    )

for m in request.messages:
    await async_worker.queue.put(partial(add_messages_task, m))
```

**每条 message 独立成 episode，各自带自己的 `uuid` 与 `reference_time`。** 由此推出四条结论：

1. ✅ **双时间戳裁决的粒度 = 单条消息** —— 「先 React 后 Vue3」只要分两条 message 投递即可被正确裁决，**前提是 `timestamp` 必须显式传**（否则全是 `utc_now()`、两条时间戳相同 → 裁决失效）。
2. ✅ `uuid` 由我们指定 → **幂等有抓手**。
3. ⚠️ 一次 POST 带 N 条 = **N 次串行 `add_episode`**，每次 20~45s（实测）→ **一批塞太多会线性拉长整批落库时间**。建议**一次 POST ≤ 3 轮**，超出则拆多次投递并顺序确认。
4. → **写入粒度的答案据此确定：一轮 = 一条 message，只投 user。** 不做「整批合成一个 episode」—— 那会让同一批次内的改口失去裁决依据，正是风险二场景的同类问题。

**`source_description` 的正确用法**：它**不进** `episode_body`（body 只有 `role(role_type): content`），属于 episode 元数据。建议用来标注批次来源（如 `"session:S123 5min-batch"`），便于日后按来源回溯/清洗。
⚠️ 它**是否参与 LLM 抽取上下文未实测** → B1 期用「同内容、不同 source_description」两条 episode 做对照实验确认。若参与，则**严禁**往里放任何可能被误抽成事实的文本。

### 3.5 幂等（两层）

- **第一层：`uuid` 承载确定性 ID**（`"cm_" + chatMessageId`）。
  ⚠️ **尚未验证**：`add_episode(uuid=...)` 在 uuid 已存在时是**抛错 / 覆盖 / 照旧重复建边**？→ **B1 的第一个验证项**（同一 uuid 投两次，观察 `/episodes` 与 `/search` 的差异）。
- **第二层：Redis `setnx`**（沿用 `SemanticScanConsumer:65-66` 现成套路），TTL 1h 覆盖最大重试窗。

### 3.6 落库确认：**202 ≠ 成功**（最容易做错的一步）

`POST /messages` 返回的 202 只代表「进了 `asyncio.Queue`」。首条 episode 实测 **21~45s** 才进 Neo4j。

| 策略 | 评价 |
|---|---|
| S0 收到 202 就 ack | ❌ **不推荐**。后台 worker 失败（embedding 超时、LLM 报错）时我们**完全不知道** → 重试机制形同虚设 |
| S1 轮询 `GET /episodes/{groupId}?last_n=N`，**按 uuid 比对** | ✅ **推荐** |
| S2 回调 | ❌ Graphiti 无 webhook 能力 |

**S1 的细节**：确认窗口 **90s**、轮询间隔 5s；**必须按 uuid 比对，只比数量是弱判据**（并发/重放会误判成功）；超时按「可重试失败」处理（重投时靠 uuid 去重）。
⚠️ `/episodes` 返回的是 graphiti-core 原始 `EpisodicNode` 列表（**不是 DTO**），字段名需实测确认是否有稳定 `uuid`；若无，退化为「数量 + valid_at 区间」双判据。→ **B1 验证项**。

### 3.7 重试分类（决定退避曲线）

| 情况 | 判定 | 动作 |
|---|---|---|
| 400 / 422 | **毒消息**（载荷错误，重试无意义） | 直接进 DLQ，**不重试** |
| 连接拒绝 / 超时 / 5xx / 502 | 可重试 | 30s → 2min → 10min，三次后 DLQ |
| 落库确认超时 | 可重试（不是毒消息） | 同上 |

★ **不要用 `@Retryable` 做 MQ 重试**：它在**同一线程内循环**，重试期间消息未 ack、消费者被占住；而 TTL+DLX 把重试**卸载到 broker**，消费者线程立即释放。两者语义不同，别混用。（`@EnableRetry` 在工程里已开，但**不要用在这条链上**。）

---

## 四、第三部分 · 三层召回与 Prompt 注入管线（The Interceptor）

### 4.1 三个风险的专项防御（用户点名要求写入方案）

#### 风险一：记忆源「神仙打架」

**先修正冲突的真实形态**（依据 §1.3 偏差 A）：Qdrant 里没有对话流水账，所以真实的三方冲突是：

| 冲突 | 场景 | 现有机制能否处理 |
|---|---|---|
| **① 笔记/资料 内容 vs 图谱事实** | 用户笔记或检回的资料里写着「Java」，而图谱当前有效偏好是「只用 Go」 | ❌ **完全没处理，且这是长期存在的** |
| **② 历史原文 vs 图谱事实** | L1 窗口里还留着用户一个月前说过、但已被图谱作废的话 | 部分（窗口只有 6 条，通常已滚出） |
| ③ 图谱内部新旧 | 用户改口 | ✅ Graphiti 已解决 |

**防御 = 双保险，绝不只是 Prompt**：

**(a) 结构性防御（首选，先于 Prompt）—— 物理分区注入，让模型不需要「猜」哪条更权威**

- `{{userGlobalMemory}}` ← **只有** Graphiti 过滤后的当前有效偏好/禁忌（XML 隔离，沿用现有 `<user_preferences>` / `<forbidden_rules>` 形态）
- `{{episodicMemory}}` ← **只有** Graphiti 的情景关系事实（带「生效于 YYYY-MM-DD」标注）
- `{{context}}` ← 笔记切片与全网引用，**块首新增一句声明**：
  > 以下为用户保存或检索到的资料，**不代表用户本人的事实与偏好**

这一句是把「资料」与「事实」在语义上切开的关键 —— 比在 Prompt 里给来源排个 1/2/3 名次更有效，因为它直接消除了「两者都在讲用户，我该信谁」的歧义源。

**(b) Prompt 防御（铁律，写成 `CoreChatPrompt.txt` 的固定文本，不做成变量）**

```
【记忆权重铁律 — 不可僭越】
1. 【短期上下文】权威性最高：用户刚刚说的、本轮明确的指令永远优先。
2. 【当前有效偏好】次之：从历史对话提炼、且已按时间裁决为「仍然生效」的稳定画像。
3. 【资料与引用】最低：笔记切片与全网引用只代表用户保存/检索到的资料，
   不代表用户本人的事实与偏好。当它与第 2 类冲突时，无条件服从第 2 类。
   例：资料中在讲 Java，而当前有效偏好是「只用 Go」→ 必须按 Go 作答，
   不得把资料里的技术栈描述成用户的个人偏好。
4. 第 1 类与第 2 类冲突时以第 1 类为准（用户改主意了）。
5. 第 2 类事实带「生效于 YYYY-MM-DD」标注，越新越权威。
```

**(c) 物理降噪（可选，二期）**：对**画像类**问句（「我平时有什么习惯 / 我的偏好是什么 / 我对什么过敏」），**跳过 Qdrant 检索**。理由：这类问题的答案只可能在图上，检索笔记只会引入风险一①。`IntentRoutingService` 已有 `EPISODIC_MEMORY` 分支可用，但需把它的语义从「本会话回溯」扩为「跨会话回溯 + 画像」——**属产品语义变更，需你点头（P3）**。

#### 风险二：「5 分钟失忆症」

**先纠正一个认知偏差**（不纠正的话，兜底方案会落空）：

- 现有 L1 载体是 `ChatMemoryProviderConfig` 的 `MessageWindowChatMemory.maxMessages = ${chat.memory.max-messages}`，而 yaml 里 `chat.memory.max-messages: 6`。
- **它是「条数窗口」不是「时间窗口」** —— 所以「至少保留 15 分钟」这个说法在当前实现下**不成立**：密集对话 5 分钟就能产出 6 条以上，事实照样被挤掉。
- 且此处 `RedisChatMemoryStore` 只配了 host/port，**未显式设 TTL** → 默认 TTL 需实测（若默认很短，Redis 侧也会丢）。
- 真正"永不丢"的持久层是 **MySQL `chat_message`**（`SemanticScanConsumer.fetchRecentUserTurns()` 就是从它读的）。

**兜底 = 三件事（按优先级）**：

1. **窗口扩容**：`chat.memory.max-messages: 6 → 20`（≈10 轮），并**实测/显式设置** `RedisChatMemoryStore` 的 TTL ≥ 30min。**最小改动、收益最大的一条。**
2. **加一道「未沉淀原文」时间窗**（结构兜底；条数窗口天然不可靠）：召回时额外从 `chat_message` 取 `created_at >= now - graphiti.recall.unsettled-window-minutes（默认 10）` 的轮次（上限 20 条），作为 `{{recentContext}}` 的独立补充段，块首标注 `[尚未沉淀为长期记忆的近期对话]`。
   这一段的语义正好补上「图谱真空期」：**哪怕 Graphiti 里什么都没有，用户 2 分钟前说的「我对海鲜过敏」也一定在。**
3. **（可选）热点快通道**：落库后立即跑一次 `MemorySignalJudge`（同一实例，零额外模型成本），命中「高危属性/禁忌」类事实就**提前 30s 投递**，把真空期从 5 分钟压到 ~40s。代价 = 每轮多一次判定（判定器本来就要在 MQ 里跑，只是位置前移）；收益 = 「过敏/禁忌」这类不可错事实不再迟到。→ 建议**一期先不做**（P4），观察真实发生率再定。

#### 风险三：Graphiti 的「过度提炼」与「幻觉」

**根因**：抽取完全交给 `qwen-plus`，它会把玩笑当事实（「我是狗」→ `(User)-[IS_A]->(Dog)`）、把零碎当实体。质量底线已确认：`structured_output_mode=json_schema` + `qwen-plus`（qwen-turbo 会退化甚至出现 `valid_at == invalid_at` 的自相矛盾，实测过）。

**四道闸**：

1. **前置实质判定（采纳用户方案）**：新增 `MemorySignalJudge`（二值：本轮是否包含「关于用户的、可长期成立的客观事实/属性/偏好」）；`qwen-turbo` + `temperature=0` + **few-shot**（教训已刻在 `IntentRouter` 注释里：qwen-turbo 弱，光给规则不够，必须喂例子）。
   ⚠️ 判定器本身是「漏检即失忆」的风险点 → **宁松勿严**（只滤纯闲聊），并提供 `graphiti.write.judge-enabled: false` 一键关断（关断 = 全量投递）。
   输入用**本轮 user 原文**（不是整批摘要）—— 粒度越细、判定越准、越省 token。
2. **投递形态本身就是闸**：`ingest.py` 的 `episode_body` 是 `role(role_type): content`，**assistant 内容不进图谱**（沿用既有裁决，避免 AI 的客串假设句被抽成用户事实）。
3. **注入侧二次消毒**：复用现成的 `PromptSanitizer.sanitizeFact()`（Phase 3 的「记忆投毒三道闸」）。Graphiti 的 fact 同样是 LLM 产物、同样属不可信输入 —— 这条现在**比 Phase 3 更必要**。
4. **事后清洗能力**：`GET /entity-edge/{uuid}` 取单条详情、`DELETE /entity-edge/{uuid}` 删单条、`DELETE /episode/{uuid}` 按 episode 删 —— 三者构成事后清洗闭环。一期先跑**影子模式**（§5）人工抽检。

### 4.2 召回编排（三层并行，硬预算）

```
用户提问
  │
  ├─(已有) L1  Redis 窗口原文 + ChatSummaryService 摘要            ── 0 网络开销
  │
  ├─(新增) L2-a 画像召回  POST /search {query: "用户的长期偏好、习惯与禁忌",
  │                                     group_ids: ["user_{id}"], max_facts: 8}
  │                                     ↑ 固定 query，embedding 天然可缓存
  ├─(新增) L2-b 情景召回  POST /search {query: <改写后的本轮问题>,
  │                                     group_ids: ["user_{id}"], max_facts: 6}
  │        两路并行发起；硬预算 800ms；超时 → 该路视为空（绝不阻塞首字）
  │
  └─(已有) L3  Qdrant 双路并发（本地知识库 / 全网）                ── 已有 4s 就绪预算
  │
  ▼ Java 侧统一收口（GraphitiMemoryRecaller）
    两路 facts 合并 → uuid 去重 →（invalidAt 过滤已在 Client 内完成）→ sanitizeFact → 分槽位
  │
  ▼ 组装 System Prompt
    {{userGlobalMemory}}  ← 画像桶（preferences / constraints 分桶 XML，沿用现有形态）
    {{episodicMemory}}    ← 情景桶（带「生效于 YYYY-MM-DD」）
    {{historySummary}}    ← L1 摘要（已有，不动）
    {{recentContext}}     ← L1 原文 + 「未沉淀原文」时间窗（风险二·第 2 条）
    {{context}}           ← L3（已有，块首加「资料 ≠ 用户事实」声明）
```

**为什么用两个 query 而不是一个**：一个 query 拿回来的 facts 无法区分「这是长期画像还是本轮相关情景」，只能靠额外 LLM 分桶。两个 query 各归其位 —— **零额外 LLM 调用**，且固定 query 的 embedding 可缓存（这正是延迟确定性的来源）。

### 4.3 伪代码（注入侧，仅示意，不含实现）

```java
private String buildUserGlobalMemory(Long userId) {   // 替换现有 buildUserMemoryBlock
    if (!graphitiProperties.isEnabled() || !graphitiProperties.getRecall().isEnabled()) {
        return "";
    }
    try {
        List<RecalledFact> facts = graphitiMemoryRecaller.recallPortrait(userId); // 内部已过滤+消毒
        return renderPreferenceBlock(facts);   // 空则返回 ""（零污染，沿用现有约定）
    } catch (Exception e) {
        log.warn("长期画像召回失败，降级为无记忆对话 | userId={}", userId, e);
        sink.next(statusEvent("memory_degraded", "长期记忆暂不可用，本轮不参考历史偏好")); // 非阻断
        return "";
    }
}
```

铁律：**注入侧任何异常都不得向上抛**（主流程绝不为记忆失败而失败）；但降级必须**可观测**（`statusEvent` + Micrometer 计数）。

### 4.4 Prompt 预算分配（防止把主模型撑爆）

| 块 | 预算 | 依据 |
|---|---|---|
| `{{userGlobalMemory}}` | ≤ 300 字 | 画像事实本来就短（Top-8 × ~30 字） |
| `{{episodicMemory}}` | ≤ 400 字 | Top-6 |
| `{{recentContext}}` | ≤ 800 字 | 20 条 × 单条截断（现有 `buildConversationContext` 已有 150 字截断逻辑可复用） |
| `{{context}}` | 已有逻辑 | 不动 |

总新增 ≤ 700 字（≈ 1k token），相对 `max-tokens: 8192` 与已有多路上下文属可接受增量。
超预算时按 **「情景桶 → 画像桶」** 顺序从尾部截断（画像比情景更稳定，优先保留）。

---

## 五、灰度与降级

- **三个开关分离**：`graphiti.enabled`（总闸）/ `graphiti.write.enabled` / `graphiti.recall.enabled` —— 支持「只写不读」的影子模式，也支持任意一侧单独回滚。
- **影子模式的必要性**：`qwen-plus` + DashScope + `json_schema` 这个组合我们**只实测过「改口」一个场景**；实体对齐、跨会话去重、长会话下的图谱质量全未验证。而「记忆污染」是很难察觉、很难回滚的失败模式 —— 宁可先只建图不注入。
- **降级姿态**：静默降级（沿用 `catch → ""`）+ 一条**不打断生成**的 notice 级 `statusEvent`（前端已有 `retrieving_memory` 事件位，需新增失败态语义）。

---

## 六、实施顺序与废弃清单

### 6.1 批次

| 批次 | 内容 | 依赖 |
|---|---|---|
| **B0** | 契约层：`GraphitiProperties` + DTO + `GraphitiMemoryClient` + 独立 `RestClient`/`ObjectMapper` + **§2.2 四个字段陷阱的防御断言** + 契约自测 | 无 |
| **B1** | 写入链：MQ 拓扑 + 三个隐患修复 + `GraphitiWriteConsumer` + `MemorySignalJudge` + 重试/DLQ + 水位 + 幂等；**首个验证项 = `uuid` 重复投递行为**（§3.5）+ `/episodes` 字段实测（§3.6） | B0 |
| **B2** | 召回链：`GraphitiMemoryRecaller` + 双 query 编排 + 预算截断 + Prompt 铁律 + `{{episodicMemory}}` 槽位 + L1 窗口扩容与时间窗兜底 | B1 |
| **B3** | 遗忘权与管理面：`SemanticMemoryController` 改读 Graphiti + 单条/按 episode 删除 + 前端「我的记忆」分层展示 | B2 |
| **B4** | 灰度：三开关 + 影子模式 + Micrometer 可观测性 + 回滚演练 | B3 |

**建议先只做 B0**：零业务风险、把所有契约陷阱一次性钉死；B0 完成后即可用真实 DTO 做端到端契约验证，再决定后面怎么走。

### 6.2 废弃清单（「不搞历史包袱」的物理动作）

**删除**：`HabitExtractor`、`HabitJudge`、`SemanticDeduplicator`、`SemanticMemoryService(Impl)`、`UserSemanticMemory(Mapper)`、`UserSemanticCandidate(Mapper)`、`ExtractedHabit(List)`、`HabitVerdict`、`SemanticRuleType`、`SemanticHabitVO`、`AssistantConfig` 里的 `habitExtractor()` / `habitJudge()` 两个 Bean、`application.yaml` 的 `semantic.*` 段、Qdrant `user_semantic_habits` 集合定义。

**改造**：`SemanticScanConsumer` → `GraphitiWriteConsumer`；`SemanticScanProducer`（保留冷却锁，消息体加水位字段）；`ChatStreamService.buildUserMemoryBlock` → Graphiti 召回；`MainChatAssistant` 加 `{{episodicMemory}}` 槽位；`CoreChatPrompt.txt` 加铁律 + 新块 + `{{context}}` 的「资料 ≠ 事实」声明；`SemanticMemoryController` → Graphiti 数据源。

**保留并复用**：`PromptSanitizer.sanitizeFact()`（消毒）、`ChatSummaryService`（L1 摘要）、`IntentRoutingService`（已有 routing 骨架）、`Bm25SparseEncoder`（稀疏检索能力）。

**数据清理**：Qdrant `user_semantic_habits` 集合直接删除；MySQL 两张表（`user_semantic_memory` / `user_semantic_candidate`）**先停止写入、暂不 drop**（生产数据不可逆删除需谨慎，观察期后再定）。

---

## 七、待你拍板的收敛项（比 v1 少了 6 个）

| # | 问题 | 我的建议 |
|---|---|---|
| **P1** | §1.3 偏差 A 的处置：确认「Qdrant 不新增对话流水账」，L3 的「原话回溯」一期就靠 MySQL `chat_message` + 时间窗/BM25（**不新建向量集合**） | 建议采纳 —— 现有 `Bm25SparseEncoder` 已够用，且避免自己制造风险一① |
| **P2** | 风险一的结构性防御：是否给 `{{context}}` 加「资料 ≠ 用户事实」块首声明 | 强烈建议加，成本一行文本 |
| **P3** | 是否把 `EPISODIC_MEMORY` 的语义从「本会话回溯」扩为「跨会话回溯 + 画像」（产品语义变更） | 建议扩，但需你确认前端文案 |
| **P4** | 风险二·第 3 条（热点快通道）一期是否做 | 建议不做，先观察 |
| **P5** | `group_id` 命名：`user_{userId}` 全局单组（沿用 v1·Q6） | 建议全局单组 |
| **P6** | 已作废事实默认不注入（沿用 v1·Q4），留开关 | 建议默认关 |
| **P7** | 灰度期验收方式：人工抽检图谱 / 内部调试页 / 导出 fact 列表 | 建议先导出 fact 列表人工抽检，一期不做调试页 |
| **P8** | 写入粒度已定（一轮一条 message，只投 user），但**一次 POST 的批次上限**取 3 轮还是更小？ | 建议 3 轮封顶（串行抽取，20~45s/条） |

---

## 附录 A · 契约速查表（据容器源码实读，2026-10-12）

| 接口 | 方法 | 约束 | 响应 |
|---|---|---|---|
| `/messages` | POST | `{group_id, messages:[{content, role_type(user/assistant/system), role(**必填字段，可 null**), uuid?, name?, timestamp?, source_description?}]}`；**每条 message 独立成 episode**；**202 异步** | `{message, success}` |
| `/search` | POST | `{group_ids[]（**可选可 null，漏传=跨租户全局**）, query(必填), max_facts=10}` | `{facts:[FactResult]}`；**不过滤 invalid_at、无 group_id** |
| `/episodes/{group_id}` | GET | `last_n` 必填 | `[EpisodicNode]`（**非 DTO**，字段需实测） |
| `/entity-edge/{uuid}` | GET / DELETE | 单条 fact 详情 / 删除 | `FactResult` / `{message,success}` |
| `/episode/{uuid}` | DELETE | 按 episode 删除 | `{message,success}` |
| `/group/{group_id}` | DELETE | **按组清理的正确姿势** | `{message,success}` |
| `/clear` | POST | ⚠️ **无参数 = 整图清空**，生产禁用 | `{message,success}` |
| `/healthcheck` | GET | — | `{"status":"healthy"}` |
| `/get-memory` | POST | 需单数 `group_id` + `center_node_uuid` + `messages[]` | 本 Phase 不使用 |

**`FactResult` 原始字段**：`uuid, name, fact, valid_at(null), invalid_at(null), created_at(必填), expired_at(null), source_node_uuid, target_node_uuid, episodes[]`
**时间序列化格式**：`astimezone(timezone.utc).isoformat()` → 形如 `2026-10-12T09:51:56.345970+00:00`（微秒 + offset）→ **Java 侧必须用 `OffsetDateTime`**。

## 附录 B · 本轮证据来源

| 断言 | 证据 |
|---|---|
| 每条 message 独立成 episode、`uuid` 可指定、`reference_time = m.timestamp` | 容器内实读 `/app/graph_service/routers/ingest.py` |
| `role` 无 default（必填）、`timestamp` 默认 `utc_now()`、`AddMessagesRequest` 仅 group_id+messages | 容器内实读 `/app/graph_service/dto/common.py`、`dto/ingest.py` |
| `FactResult` 字段与序列化格式、`SearchQuery.group_ids` 可空、`max_facts=10` | 容器内实读 `/app/graph_service/dto/retrieve.py` |
| `/entity-edge/{uuid}` 支持 GET、`/episode/{uuid}` 支持 DELETE、`/clear` 无参数 | 容器内实读 `routers/retrieve.py`、`routers/ingest.py` |
| L1 = `MessageWindowChatMemory(maxMessages=6)` + `RedisChatMemoryStore(无显式 TTL)` | `ChatMemoryProviderConfig.java` + `application.yaml: chat.memory.max-messages: 6` |
| L1 原文实际经 `chatMemoryProvider.get(sessionId).messages()` 读取 | `ChatStreamService.buildConversationContext()` |
| Qdrant 两集合及各自用途 | `application.yaml: spring.data.qdrant.collections` |
| MQ 三隐患（无 DLX / catch-all / armed 无条件释放） | `RabbitSemanticConfig.java`、`SemanticScanConsumer.java`、`SemanticScanProducer.java` |
| 注入点与槽位名 | `CoreChatPrompt.txt`、`MainChatAssistant.java`、`ChatStreamService.java:271 / 657 / 922` |
| 废弃面（Phase 3 全部类与 Bean） | 全量 grep `SemanticMemoryService|HabitExtractor|HabitJudge|SemanticDeduplicator` |
