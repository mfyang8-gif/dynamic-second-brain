# Phase 4 · Graphiti 记忆微服务集成 · 架构设计方案草案

> 状态：**草案，等待 Review 拍板。本文档不含最终业务逻辑代码**（DTO / 接口签名属于契约设计，已给出）。
> 撰写日期：2026-10-12
> 服务端：`http://192.168.88.130:8088`（`custom-graphiti:v2` + `neo4j:5.26.0`，已热修复并验收）
> 客户端工程：`Mini_Nodepad`（Spring Boot 3.4.0 / Java 21 / LangChain4j 1.15.0-beta25）

---

## 0. 侦察结论：先对齐事实，再谈设计

### 0.1 客户端工程实况（已逐文件核实）

| 项 | 实况 | 对设计的影响 |
|---|---|---|
| 框架 | Spring Boot **3.4.0**、Java **21** | 可用 `RestClient`（Spring 6.1+）、虚拟线程 |
| LLM 栈 | **LangChain4j 1.15.0-beta25**（非 Spring AI） | 不引入任何 Spring AI 依赖 |
| HTTP 客户端 | 已有共享 `RestTemplate`（connect 5s / **read 60s**），用于 Pandoc / Rerank | **不可复用**，见 §1.1 |
| 重试 | `@EnableRetry` **已在** `MiniNodepadApplication`，`AbstractStudioGenerator` 已用 `@Retryable` | 声明式重试零新增依赖 |
| MQ | `spring-boot-starter-amqp`，`default-requeue-rejected: false` | 现有拓扑**无 DLQ**，见 §2.1 |
| 全局 ObjectMapper | `JacksonObjectMapper` 注册为 `@Primary`，**`LocalDateTime` 被钉死为 `yyyy-MM-dd'T'HH:mm:ss`** | 🔴 **关键陷阱**，见 §1.4 第 1 条 |
| 现有记忆 | `user_semantic_memory`(MySQL) + `user_semantic_habits`(Qdrant) + `HabitExtractor`/`HabitJudge` | 与新链路的**关系必须拍板**，见 §3.1 Q1 |
| 注入点 | `ChatStreamService.buildUserMemoryBlock(userId)` → `{{userGlobalMemory}}` | 天然接入位 |
| 路由 | `IntentRoutingService.determineIntent()` 五分类，是流式管线第一个节点 | 召回分流可复用它，见 §3.2 B |
| 既有缺口 | `UserIntent.EPISODIC_MEMORY` 分支注释：「**用户 2026-10-12 裁决：记忆检索 gap 本轮不修**」 | 这正是 Phase 4 要补的洞 |

### 0.2 服务端契约（从容器源码 + OpenAPI + 实调三方交叉确认）

**写入** `POST /messages` → **202**（异步）

```json
{ "group_id": "string",
  "messages": [ { "content": "string(必填)", "role_type": "user|assistant|system(必填)",
                  "role": "string(必填)", "uuid": "string?",
                  "name": "string?", "timestamp": "ISO8601?", "source_description": "string?" } ] }
```

容器内 `ingest.py:59-68` 的实际映射 —— **这是契约的关键，文档里没有**：

```python
await graphiti.add_episode(
    uuid=m.uuid,                                   # 显式 uuid → 可作幂等键
    group_id=request.group_id,
    name=m.name,                                   # episodic node 名
    episode_body=f'{m.role or ""}({m.role_type}): {m.content}',   # ★ content 会被包一层
    reference_time=m.timestamp,                    # ★ 不传则服务端 utc_now()
    source=EpisodeType.message,
    source_description=m.source_description,
)
```

**检索** `POST /search` → 200

```json
{ "facts": [ { "uuid":"...", "name":"PREFERRED_FRONTEND_FRAMEWORK_OVER",
               "fact":"user changed ... from React to Vue3",
               "valid_at":"2026-10-12T09:51:56.345970+00:00",
               "invalid_at":null, "created_at":"...", "expired_at":null,
               "source_node_uuid":"...", "target_node_uuid":"...",
               "episodes":["3850349c-..."] } ] }
```

`SearchQuery` 定义（`dto/retrieve.py:8-13`）：`group_ids: list[str] | None = None`、`query: str`、`max_facts: int = 10`。
**只有 `query` 是必填** —— 这是 §1.4 第 3 条漏洞的根源。

### 0.3 已实测确认的「震撼效果」（能力基础）

双时间戳裁决在 v2 上稳定复现：

```
(user)-[USES]->(React)   valid_at=11:40:08.356448  invalid_at=11:40:29.364573  expired_at=11:40:43.978353
(user)-[PREFERRED_FRONTEND_FRAMEWORK_OVER]->(Vue3)  valid_at=11:40:29.364573  invalid_at=None
```

作废时刻与新事实生效时刻**毫秒级相等**，同组其它事实（Java / Spring Boot）未受误伤。
这正是 `user_semantic_memory` 的 `uk_user_fact` + 纯累加模型**结构上做不到**的事。

---

# 第一部分 · 通信管道与 DTO 设计

## 1.1 技术选型：**推荐 `RestClient`**

| 维度 | RestClient ✅ | WebClient | OpenFeign |
|---|---|---|---|
| 调用形态匹配度 | 本工程调用点**全是阻塞式**（MQ 消费者线程、`Thread.startVirtualThread` 内的同步编排、`@Retryable`） | 响应式优势在此为零，反而逼出 `.block()` 反模式 | 阻塞，匹配 |
| 新增依赖 | **0**（Boot 3.2+ 自带） | reactor 已在（`langchain4j-reactor`），但会引入响应式心智负担 | 需引 **spring-cloud-openfeign** —— 本工程当前**完全没有 Spring Cloud**，为 1 个 client 引整条版本线不划算 |
| 超时/错误控制透明度 | 高（每 client 独立 `ClientHttpRequestFactory`） | 中 | 低（Feign 自带重试器会与 `@Retryable` **叠加重试**，实际调用次数被放大） |
| 与既有代码心智一致性 | 高（与 `RestTemplate` 同族，迁移成本最低） | 低 | 中（声明式，但注解魔法多） |
| Java 21 虚拟线程 | 阻塞式在虚拟线程下成本极低，无需响应式 | 优势被虚拟线程抵消 | 同左 |

**结论：`RestClient` + 独立 `RestClient.Builder`。**

**三条硬约束（必须遵守，否则会踩现成的坑）：**

1. **绝不复用现有 `restTemplate` Bean。** 它 read timeout = **60s**。若 MQ 消费者用它调 Graphiti，一次网络抖动就会把这根消费线程钉死 60 秒 —— 而 Graphiti 侧 `AsyncWorker` 是**单协程串行**消费，堆积会立刻反压。
2. **绝不复用全局 `@Primary ObjectMapper`。** 见 §1.4 第 1 条的实测分析。Graphiti client 必须自建 `ObjectMapper`（`FAIL_ON_UNKNOWN_PROPERTIES=false` 以兼容服务端加字段）。
3. **超时必须分档**，不能一刀切：

| 接口 | connect | read | 理由 |
|---|---|---|---|
| `POST /messages` | 2s | 5s | 服务端只入队即返 202，本身是毫秒级 |
| `POST /search` | 2s | 8s | 含 DashScope embedding + Neo4j 向量检索；**且在聊天关键路径上**，必须有硬预算 |
| `GET /episodes/{g}` | 2s | 5s | 仅查询，用于落库确认轮询 |

**配置项建议（放 `application.yaml`，与既有 `qdrant` / `rag` 风格一致）：**

```yaml
graphiti:
  base-url: ${GRAPHITI_BASE_URL:http://192.168.88.130:8088}
  enabled: true                 # 总开关（灰度/回滚用，见 §4）
  write:
    connect-timeout-ms: 2000
    read-timeout-ms: 5000
  search:
    connect-timeout-ms: 2000
    read-timeout-ms: 8000
    max-facts: 10
    # 召回总预算，超过则放弃记忆注入（降级为普通对话）
    budget-ms: 1500
  group-id-prefix: "user_"      # group_id 命名规范，见 §3.1 Q7
```

## 1.2 DTO 设计（契约草案）

包位置建议：`com.yangmf.mini_nodepad.ai.graphiti.dto`（与 `ai/rag/`、`ai/component/` 并列，不污染现有 `pojo/dto`，因为后者是**对外 API 契约**，而这些是**内部服务间契约**）。

### 写入侧

```java
/** 对应服务端 AddMessagesRequest */
public record GraphitiAddMessagesRequest(
    @JsonProperty("group_id")  String groupId,
    @JsonProperty("messages")  List<GraphitiMessage> messages
) {}

/** 对应服务端 Message —— 注意 wire 上是 snake_case */
public record GraphitiMessage(
    @JsonProperty("content")            String content,        // 必填
    @JsonProperty("role_type")          String roleType,       // 必填：user|assistant|system
    @JsonProperty("role")               String role,           // 必填
    @JsonProperty("uuid")               String uuid,           // ★ 传确定性 ID，换幂等 + 可确认
    @JsonProperty("name")               String name,           // episodic node 名（人类可读短标签）
    @JsonProperty("timestamp")          OffsetDateTime timestamp,  // ★ 必须显式传
    @JsonProperty("source_description") String sourceDescription   // 标记来源：SESSION_SUMMARY / HABIT_SETTLE
) {}
```

**为什么用 `@JsonProperty` 而不是全局 `SNAKE_CASE` 命名策略**：`JacksonObjectMapper` 是 `@Primary` 的全局 Bean，改它的命名策略会波及**所有** `VO`/`DTO` 的序列化（例如 `ChatMessageVO`、`StudioArtifactListVO`），前端契约会被静默破坏。爆炸半径过大 —— 局部显式标注才是对的。

### 响应侧

```java
/** 对应服务端 Result */
public record GraphitiResult(String message, boolean success) {}

/** 对应服务端 SearchResults */
public record GraphitiSearchResponse(List<GraphitiFact> facts) {}

/** 对应服务端 FactResult（dto/retrieve.py:16-29 逐字段对齐） */
public record GraphitiFact(
    String uuid,
    String name,              // ★ 边标签，模型自由生成、不稳定 → 禁止作业务分支依据
    String fact,
    OffsetDateTime validAt,
    OffsetDateTime invalidAt, // ★ 非 null = 已作废
    OffsetDateTime createdAt,
    OffsetDateTime expiredAt,
    String sourceNodeUuid,
    String targetNodeUuid,
    List<String> episodes
) {}
```

### 出口模型（对上层暴露的形态）

**关键设计：不把 `GraphitiFact` 直接漏给上层。** 上层只该拿到已清洗的领域对象：

```java
/** 记忆召回结果（已完成作废过滤 + 消毒），上层无法绕过清洗 */
public record RecalledFact(
    String uuid,        // 保留：遗忘权删除时要用 DELETE /entity-edge/{uuid}
    String text,        // 已消毒的 fact 正文
    String relation,    // name，仅作日志/可视化，不参与逻辑分支
    OffsetDateTime validAt
) {}
```

## 1.3 清洗层级：**推荐 Service 层，且以「唯一出口方法」收敛**

你问的「Client 拦截器层 vs Service 层」，我的结论是 **Service 层**，理由分四层，从强到弱：

**① 语义归属错了。** `invalid_at != null` 是**领域语义**（事实的时效作废），不是传输瑕疵。`ClientHttpRequestInterceptor` 的职责是横切**传输**问题（鉴权头、traceId、重试计数、编解码）。把领域规则塞进传输层，是典型的层次错配 —— 未来接手的人会去拦截器里找业务规则，找不到。

**② 会产生信息黑洞（这条最致命）。** 一旦在拦截器里无条件丢弃，**任何调用方都拿不到了，且无法在调用点绕过**。而你**很可能需要**它：
- 排障场景：「为什么模型一直说 React？」→ 你需要看到那条 `invalid_at` 已置位的证据；
- 产品场景：前端「记忆变更历史」页，展示「用户曾用 React，已于 10-12 改为 Vue3」；
- 测试场景：验收双时间戳裁决本身就要读作废边。

拦截器把这个能力**永久焊死**了。

**③ 技术上别扭且昂贵。** `ClientHttpRequestInterceptor` 拿到的是 `byte[]` body。要在那里过滤，就得自己 `ObjectMapper` 反序列化一遍 → 过滤 → 再序列化回 JSON → 让 RestClient 的消息转换器**再解析一次**。等于绕开框架做双重解析，既慢又脆（且无法享受 `FAIL_ON_UNKNOWN_PROPERTIES=false` 的兼容性）。

**④ 本工程的既有惯例就是 Service 层收敛。** `SemanticMemoryServiceImpl.resolveFinalHash()`（召回→仲裁→定 hash）、`ChatStreamService.buildUserMemoryBlock()` 里的 `sanitizeFacts()` —— 都是「Service 层单一收口 + 上层拿到的就是干净的」。沿用它，一致性最好。

### 落地形态：唯一出口方法

```java
public interface GraphitiMemoryClient {

    /** 唯一召回出口：入参 groupIds 强制非空 → 请求 → 滤除 invalidAt != null → 消毒 → 返回 */
    List<RecalledFact> recallActive(Long userId, String query, int maxFacts);

    /** 显式命名的「包含作废事实」出口，仅供排障/历史页使用，杜绝误用 */
    List<RecalledFact> recallIncludingInvalidated(Long userId, String query, int maxFacts);

    /** 写入（内部完成 DTO 组装 + 202 语义处理） */
    void addMessages(Long userId, List<GraphitiMessage> messages);
}
```

这样「最优雅」的定义就落在两处：
- **收口点在 Service 层的唯一出口方法** → 上层**不可能**忘记过滤（因为拿到的就是 `List<RecalledFact>`，没有 `invalidAt` 字段可漏）；
- **可观测性不为优雅让路** → 需要作废事实时走显式命名的第二个方法，而不是去绕拦截器。

## 1.4 契约陷阱清单（8 条，全部为实测/源码确认，不是推测）

| # | 陷阱 | 证据 | 防御 |
|---|---|---|---|
| 1 🔴 | **全局 `JacksonObjectMapper` 无法解析 Graphiti 时间戳** | `JacksonObjectMapper:29` 把 `LocalDateTime` 钉死为 `yyyy-MM-dd'T'HH:mm:ss`；Graphiti 返回 `2026-10-12T09:51:56.345970+00:00`（**微秒 + offset**）。且 `WebMvcConfiguration:72-75` 将其注册为 `@Primary`，Boot 的 `HttpMessageConverters` 会采纳它 | ① DTO 一律用 **`OffsetDateTime`**（无自定义反序列化器，走默认 JSR-310，原生支持 offset+微秒）；② Graphiti client **自建 ObjectMapper**，与 Web 层解耦（双重保险） |
| 2 🔴 | `/search` 响应**不含 `group_id`** | `FactResult`（`dto/retrieve.py:16-29`）无该字段；实调确认 | 客户端**无法自证**某条 fact 属于谁 → 只能在请求侧硬校验 `groupIds` 非空；服务端漏洞建议同步修（见下） |
| 3 🔴 | `group_ids` **可选可 null**，漏传 = 全局跨租户检索，且 **HTTP 200** | `SearchQuery.group_ids: list[str] \| None = None`；四组对照实测：不传→10 条跨组，传不存在组→0 条 | DTO `@NotEmpty` + client 内在**发请求前**断言非空（fail-fast 在本地，而不是赌服务端）；**服务端建议补：空 group_ids 直接 400** |
| 4 🔴 | `POST /clear` **无任何参数 = 整图清空** | OpenAPI `/clear` 无 parameters | 生产路径**禁止调用**；按组清理用 `DELETE /group/{group_id}` |
| 5 🟠 | `Message.timestamp` 服务端默认 `utc_now()` | `dto/common.py:25` `default_factory=utc_now` | **必须显式传**会话真实时间。否则图谱时间线变成"入库时刻"，`invalid_at` 裁决的语义基准会整体漂移（首条落库延迟实测 21~45s） |
| 6 🟠 | `content` 会被包一层再入库 | `ingest.py:64` `episode_body=f'{role}({role_type}): {content}'` | 写"习惯短语"时要意识到正文前缀；纯事实写入建议 `role_type=user, role=user` |
| 7 🟠 | 边标签 `name` 由模型自由生成、**不稳定** | 同一改口事件实测出现过 `USES` / `PREFERRED_FRONTEND_FRAMEWORK` / `PREFERRED_FRONTEND_FRAMEWORK_OVER` | 禁止把 `name` 用于业务分支或硬编码 Cypher；只能做日志/展示 |
| 8 🟡 | `/search`、`/get-memory` **都不过滤已作废事实** | 实调：原样返回含 `invalid_at != null` 的一条 | 客户端过滤（即 §1.3）；`/get-memory` 另有独立契约（要 `group_id` 单数 + `center_node_uuid` + `messages`），**本 Phase 不使用** |

---

# 第二部分 · 结合 RabbitMQ 的异步写入策略

## 2.1 现状盘点（先说清楚现在有什么、缺什么）

现有拓扑（`RabbitSemanticConfig`）：

```
Producer ──(默认交换机, rk=semantic.scan.delay.queue)──▶ semantic.scan.delay.queue
                                                          TTL=5min, DLX=semantic.scan.exchange/rk=scan
                                                                    │ 过期
                                                                    ▼
                                            semantic.scan.exchange ─(rk=scan)─▶ semantic.scan.queue
                                                                                       │
                                                                        SemanticScanConsumer.onScanMessage()
                                                                        （取最近 10 条 user 轮次 → HabitExtractor → processAndPromote）
```

**三个必须先修的问题（否则 Graphiti 写链路会继承同样的病）：**

| # | 问题 | 后果 |
|---|---|---|
| 1 | `semantic.scan.queue` **未声明 DLX**，且 `default-requeue-rejected: false` | 消费抛异常 → 消息被 reject 后**静默丢弃，无死信、无痕迹** |
| 2 | `SemanticScanConsumer` 是 catch-all **吞掉所有异常** | **完全没有重试** —— Graphiti 一旦 502，这一次记忆沉淀就永久丢失，且只是 `log.error` 一行 |
| 3 | 冷却锁 `armed:{sessionId}` 在 `finally` 中无条件释放 | 即使消费失败也解锁 → 用户继续聊会重新武装 → 会重扫；但**已丢失的那一轮不会补** |

> 顺带建议：这 3 条对**现有习惯沉淀**同样是隐患，修复成本很低，可以顺带一起改。

## 2.2 新增拓扑设计（独立链路，不污染 scan 队列）

**核心决策：Graphiti 写链路独立建拓扑，不复用 `semantic.scan.*`。** 理由：两者失败语义不同（scan 失败可等下个静默期自然重扫，Graphiti 写入失败必须重试/告警），混在一条队列里会让重试策略无法分别配置。

```
┌──────────────── graphiti.write.exchange (direct, durable) ────────────────┐
│                                                                            │
│  rk=write                                              rk=retry            │
│      │                                                     ▲               │
│      ▼                                                     │               │
│  graphiti.write.queue                              graphiti.write.retry.queue
│  ├ x-dead-letter-exchange = graphiti.write.dlx      (无消费者)               │
│  └ x-dead-letter-routing-key = dead                 ├ TTL = 退避值           │
│      │                                              └ x-dead-letter-exchange = graphiti.write.exchange
│      ▼                                                 x-dead-letter-routing-key = write
│  GraphitiWriteConsumer                                     │
│      │                                                     │
│      ├─ 成功(202+确认) ────────────────────▶ ack            │
│      ├─ 可重试失败 & n<maxRetry ──▶ publish(rk=retry,     ─┘
│      │                              header x-retry-count=n+1)
│      ├─ 可重试失败 & n>=maxRetry ──▶ publish(rk=dead, → graphiti.write.dlx)
│      └─ 不可重试（400/422）─────────▶ publish(rk=dead)  ← 毒消息不浪费重试
│                                                             │
│                                                             ▼
│                                                    graphiti.write.dlq (终态)
│                                                    + 告警 + 人工/定时补偿
└────────────────────────────────────────────────────────────────────────────┘
```

**拓扑命名（与现有 `semantic.scan.*` 并列，风格一致）：**

```
graphiti.write.exchange        = graphiti.write.exchange     (DirectExchange, durable)
graphiti.write.queue           = graphiti.write.queue        (durable, DLX→dlx)
graphiti.write.retry.queue     = graphiti.write.retry.queue  (durable, TTL=动态, DLX→exchange)
graphiti.write.dlx             = graphiti.write.dlx          (DirectExchange, durable)
graphiti.write.dlq             = graphiti.write.dlq          (durable, 终态)
```

**消息体（放 `mq` 包 —— 注意 `JacksonSemanticConfig` 那个 `addTrustedPackages` 白名单必须同步登记，否则会被静默丢弃！见 `RabbitSemanticConfig:66-75` 那段血泪注释）：**

```java
public record GraphitiWriteMessage(
    String msgId,           // 幂等键 + 透传给 Message.uuid
    Long   userId,          // MQ 线程无 HTTP 上下文，必须随消息体走（禁触 BaseContext）
    String groupId,         // 已拼接好的 group_id
    String sourceDescription,
    List<GraphitiMessage> messages
) implements Serializable {}
```

> ⚠️ `RabbitSemanticConfig.jacksonMessageConverter()` 的白名单是**包名精确 equals**，新消息类若不在已登记包内会 `IllegalArgumentException` → 因 `default-requeue-rejected=false` **静默丢弃**。若新建 `mq/graphiti` 子包，必须在 `addTrustedPackages` 里显式追加。

## 2.3 失败分类与重试策略

**先分类，再谈重试** —— 对 400 重试 100 次也是 400。

| 情形 | 判定依据 | 动作 |
|---|---|---|
| 202 / 200 且落库确认通过 | 见 §2.5 | `ack` |
| 连接拒绝 (`ConnectException`) / 超时 (`SocketTimeoutException`) / 5xx | 瞬时故障 | 进 retry 队列，**最多 3 次**，退避 **30s → 2min → 10min** |
| **400 / 422** | **毒消息**（如 `messages` 为空、字段名错） | **直接 DLQ，不重试** —— 重试无意义，只会拖慢队列 |
| 401 / 403 | 配置错误（未来若加鉴权） | 直接 DLQ + **告警**（不是瞬时问题） |
| 落库确认超时（202 已收但 episode 未出现） | 见 §2.5 | 视为可重试失败，进 retry |

**退避实现**：用 RabbitMQ 核心的 **TTL + DLX**（与现有 `SCAN_DELAY_MS` 同一手法，不依赖延迟插件），每个重试档位一个队列，或单队列 + 动态 TTL（`x-message-ttl` 需要 per-queue，故**建议 3 个静态重试队列**，各自固定 TTL）：

```
graphiti.write.retry.30s.queue     TTL=30_000
graphiti.write.retry.2m.queue      TTL=120_000
graphiti.write.retry.10m.queue     TTL=600_000
```

（单队列 + TTL 的方案是错的：RabbitMQ 队列的 TTL 是队列级属性，队首消息的 TTL 会挡住后面的短 TTL 消息 —— 即所谓「队头阻塞」。）

**`@Retryable` 不要用在这条链上。** 工程里 `@EnableRetry` 已开，但**MQ 消费链路的重试必须交给 MQ 本身**：`@Retryable` 是在**同一线程内循环**，重试期间消息未 ack，消费者被占住；而 TTL 队列把重试**卸载到 broker**，消费者线程立即释放。两者语义不同，别混用。

## 2.4 幂等设计

**这是重试机制能不能真正成立的前提。** at-least-once 必然重复投递。

**第一层（推荐，需先实测验证）：用 `Message.uuid` 承载确定性 ID。**

`ingest.py:61` 是 `uuid=m.uuid` —— 即 episode UUID 由我们指定。若把它设为我们自己的确定性 ID（如 `chat_{chatMessageId}` 或直接 `msgId`），**理论上**重复投递会同 uuid 覆盖而非重复。**但这一点我尚未实测验证**：graphiti-core `add_episode(uuid=...)` 在 uuid 已存在时是抛错、覆盖、还是照旧重复建边？—— **列为 B1 阶段的第一个验证项**。

**第二层（兜底，沿用工程现成套路）：Redis `setnx` 幂等键。**

`SemanticScanConsumer:65-66` 已经这么做了（`semantic:consume:{msgId}`，TTL 10min）。同样手法：

```
graphiti:write:dedup:{msgId}   TTL = 覆盖最大重试窗口（建议 1h，> 30s+2m+10m 总和）
```

**关键：`uuid` 的第二个用途 = 落库确认的比对键**（见 §2.5）。

## 2.5 落库确认：**202 不等于成功**（这条最容易做错）

`POST /messages` 返回 202 时，episode **还没进 Neo4j**（首条实测 21~45s 才落库）。所以：

| 策略 | 做法 | 评价 |
|---|---|---|
| S0 发即忘 | 收到 202 就 `ack` | ❌ **不推荐**。202 只代表"入队成功"，Graphiti 后台 worker 失败（如 embedding 超时、LLM 报错）时我们**完全不知道** → 重试机制形同虚设 |
| S1 轮询确认 | 投递后轮询 `GET /episodes/{group_id}?last_n=N`，按 **uuid 比对**确认 episode 出现 | ✅ **推荐** |
| S2 回调 | Graphiti 无 webhook 能力 | ❌ 排除 |

**S1 的细节要求：**
- **必须按 uuid 比对，不能只比数量。** 「episode 数量够了」是弱判据（并发/重放时会误判成功）。
- 确认窗口建议 **90s**（覆盖实测 45s 最坏值 + 余量），轮询间隔 5s。
- 超时未确认 → 按"可重试失败"处理（重试时会因 uuid 相同而有机会去重，这正是 §2.4 第一层的价值）。
- 确认逻辑本身是**额外的一次 HTTP**，要考虑它对消费者吞吐的影响：因此下面并发度要压低。

**消费者并发度建议：`concurrency=1`、`prefetch=1`。**
理由：Graphiti 侧 `AsyncWorker` 是**单协程串行**消费 `asyncio.Queue`（`ingest.py:13-25`），压再多也只是在服务端排队，而我们这边反而多占线程 + 多占确认轮询的带宽。串行 + 确认，吞吐稳定且可控。

---

# 第三部分 · 记忆召回策略（**需与你拍板，本文档不给结论**）

## 3.1 首要问题：Graphiti 与现有「三期语义记忆」是什么关系？

**这是所有其它决策的前提，必须你先定。** 现状 `Phase 3` 那套（`HabitExtractor` → `HabitJudge` → Qdrant 查重 → `user_semantic_memory` 晋升）是**为「改口」专门设计过的**（`SemanticMemoryServiceImpl` 的类注释里完整记录了"余弦衡量句子形式相似度而非事实同一性"的实证：`偏好 Vue3 vs 偏好 React = 0.8473`，而真正该合并的只有 `0.7483` —— 不存在可用阈值）。而 Graphiti **原生就解决了**这件事。

| 方案 | 含义 | 代价 |
|---|---|---|
| **A. 并存双轨** | MySQL 继续管"晋升后的稳定习惯"，Graphiti 只补"情景记忆 + 改口覆盖" | 两套真相源，可能给出**冲突记忆**（同一偏好两处不一致） |
| **B. Graphiti 上位替换** | 废弃 `HabitExtractor`/`HabitJudge`/Qdrant 查重链路，召回全走 Graphiti | 扔掉 Phase 3 全部工程投资；图谱不可读（调优只能看 fact 文本）；边标签不稳定；**弱模型会降级**（实测 qwen-turbo 会出现 `valid_at == invalid_at` 的自相矛盾，必须 `qwen-plus` 及以上） |
| **C. 只补缺口** | Graphiti 仅用于 `UserIntent.EPISODIC_MEMORY` 分支（当前注释写着"记忆检索 gap 本轮不修"），不动 Phase 3 | 收益最小但风险最低；一次只动一个变量 |

## 3.2 召回时机：三个策略选项

### 选项 A — 静态全量注入（现状的直接延伸）

每题必查 Graphiti，用**固定 query**（如"用户的技术栈偏好与禁忌"）召回，Top-N 注入 `{{userGlobalMemory}}`。

- ✅ 延迟**确定性最高**（固定 query 的 embedding 可缓存）；Prompt 每轮稳定 → 利于 Prompt Cache；实现最简单（替换 `buildUserMemoryBlock` 的内部数据源即可，注入点一个字不用改）
- ❌ **开销与记忆量成正比**，不随问题变化 → 闲聊也付一次 HTTP；检索的是"通用画像"而非"当前问题相关"

### 选项 B — 意图路由分流（复用现有五分类）

复用 `IntentRoutingService.determineIntent()`（它已是流式管线**第一个节点**，零额外成本）：

| 意图 | 记忆策略 |
|---|---|
| `GENERAL_CHAT` | 仅注入**长期画像**（缓存），或完全不召回 |
| `KNOWLEDGE_SEARCH` / `WEB_SEARCH` | 画像 + **按当前问题 query 做一次召回** |
| `HYBRID` | 同上（与双路并发天然合拍） |
| `EPISODIC_MEMORY` | **正是要修的缺口**：当前只喂本会话最近 10 条（`buildConversationContext(sessionId, 10)`），改为叠加 Graphiti 跨会话召回 |

- ✅ 复用已投入的路由能力；闲聊不付 HTTP；与「双路并发」架构天然契合
- ❌ 意图分类本身会错（`IntentRouter` 注释里记录了必须给 few-shot 才过得去的误判史）；**EPISODIC_MEMORY 的语义要从"本会话回溯"扩展为"跨会话回溯"，是产品语义变更，需你确认**

### 选项 C — 冷热双层 + 并行预算内取用（最贴合你现有架构）

把记忆当成**第三条并发路**，与现有 `DualPathRetrievalService` 同构：

```
                    ┌─ 热层：Redis 缓存的长期画像（0 网络开销，MQ 写入后异步刷新）
题目进入 ───────────┼─ 冷层：Graphiti /search（并行发起，硬预算 800ms）
                    └─ 到点未回 → 静默放弃冷层，只用热层
```

- ✅ **延迟安全**（首字永远不被记忆服务拖慢）；与 `rag.dual-path.local-ready-timeout-ms: 4000` 的既有哲学完全一致；Prompt 结构稳定
- ❌ 复杂度最高；需要一套**缓存失效**策略（写入后何时刷新？MQ 消费成功即刷新？还是 TTL 兜底？）；冷层命中率取决于预算给得够不够

### 选项 D（仅列出，**与既有决策冲突，不建议**）

把记忆做成**工具**（Function Calling / MCP `recall_memory`），让模型自己决定何时查。

⚠️ **与你 2026-10-12 的裁决直接冲突**：`AssistantConfig.mainChatAssistant()` 的注释写明「**摘除 mcpToolProvider**……主模型回归纯流式生成，不再进入 Function Calling 思考循环：少一次工具往返、首字更快、输出完全确定性」。列出仅为完整性。

## 3.3 我的疑问（等你拍板）

**Q1（前提）** Graphiti 与现有三期语义记忆的关系 → 选 A / B / C？
**Q2（写入粒度）** 写**原始 user 轮次**（让 Graphiti 自己抽取）还是写**5 分钟聚合摘要**（`source_description=SESSION_SUMMARY`），还是两者都写？两者都写会不会让同一事实重复入图（Graphiti 自带实体去重，但**多消耗 LLM 调用与落库延迟**）？
**Q3（短期/长期/情景 的三层权重）** 现有 Prompt 里 `{{historySummary}}`（短期摘要）、`{{userGlobalMemory}}`（长期习惯）各占一块。Graphiti 的情景记忆是**并入 `{{userGlobalMemory}}`** 还是**新开一块 `{{episodicMemory}}`**？冲突时谁赢（例如「长期禁忌：不要写注释」vs 本次会话「这段代码帮我加注释」）？
**Q4（作废事实要不要显式告知模型）** 过滤掉 vs 注入「用户曾用 React，已于 2026-10-12 改为 Vue3」。后者对"为什么改了 / 之前用什么"这类问题有用，但会稀释注意力，且可能诱导模型复述旧概念 —— 而你的 `CoreChatPrompt` 核心纪律第 3 条**明令禁止重复已说过的内容**。两者相容吗？
**Q5（时效权重）** 是否需要按 `valid_at` 做新近度加权？还是 Graphiti 的语义相似度本身足够？
**Q6（group_id 命名规范）** 用 `user_{userId}`？还是 `user_{userId}:ws_{bookId}`（按知识库隔离）？后者会让同一用户在不同知识库里"记忆不连续" —— 这是**产品决策**，不是技术决策。
**Q7（用户可见性与遗忘权）** 现有 `deleteHabit` 走 MySQL 逻辑删除 + 前端「我的记忆」页。Graphiti 侧对应 `DELETE /entity-edge/{uuid}`（我们已能从 `/search` 拿到 uuid）—— 要不要在同一页同时展示与删除图谱事实？若要，谁来编排"双删"？
**Q8（降级姿态）** 记忆服务不可用时：**静默降级为无记忆继续作答**（沿用 `buildUserMemoryBlock` 现在的 `catch → return ""`），还是**在 SSE 里下发 `statusEvent` 告知用户**（前端已有 `retrieving_memory` 事件位）？
**Q9（灰度方式）** 是否先跑一段**影子模式**（只写不读，即建图但注入恒为空），确认图谱质量（对齐、去重、改口裁决）达标后再开启注入？

---

# 第四部分 · 实施顺序（**待你拍板后才动手，本轮不写业务代码**）

| 批次 | 内容 | 依赖 |
|---|---|---|
| **B0** | 契约层：DTO + `GraphitiMemoryClient` + `GraphitiProperties` + 独立 `RestClient`/`ObjectMapper` + **§1.4 八条陷阱的防御断言** + 契约自测（含 §1.4 第 1、2、3 条的针对性用例） | 无 |
| **B1** | 写入链：MQ 拓扑 + `GraphitiWriteConsumer` + 重试/DLQ + 幂等 + 落库确认；**第一个验证项 = `Message.uuid` 重复投递的行为**（§2.4） | B0 + §3.1 Q1/Q2 |
| **B2** | 召回链：按 §3.2 拍板结果实现 + `buildUserMemoryBlock` 改造 | B1 + §3.2 全问 |
| **B3** | 可观测性：Micrometer 打点（actuator 已引入）+ 前端 `statusEvent`；顺带修 §2.1 的三个既有隐患 | B2 |
| **B4** | 灰度：`graphiti.enabled` 开关 + 影子模式 + 回滚演练 | B3 |

**建议先只做 B0。** 它零业务风险、把所有契约陷阱一次性钉死，而且 B0 完成后你就能拿真实 DTO 去做端到端契约验证，再决定后面怎么走。

---

## 附录 A · 契约速查表

| 接口 | 方法 | 关键约束 | 响应 |
|---|---|---|---|
| `/messages` | POST | `group_id` + `messages[]`（`content`/`role_type`/`role` 必填）；**202 异步** | `{message, success}` |
| `/search` | POST | **`group_ids` 必填（数组！）**；`query` 必填；`max_facts` 默认 10 | `{facts:[FactResult]}`，**不过滤 invalid_at、不含 group_id** |
| `/episodes/{group_id}` | GET | `last_n` 必填（query）。落库确认用 | `[episode]` |
| `/group/{group_id}` | DELETE | 按组清理（**正确姿势**） | `{message, success}` |
| `/clear` | POST | ⚠️ **无参数 = 整图清空**，生产禁用 | — |
| `/healthcheck` | GET | 健康检查 | `{"status":"healthy"}` |
| `/get-memory` | POST | 需 `group_id`(单数) + `center_node_uuid` + `messages` | **本 Phase 不使用** |
| `/entity-edge/{uuid}` | DELETE | 遗忘权删除单条事实 | `{message, success}` |

## 附录 B · 本方案的证据来源

- 客户端：逐文件通读 `pom.xml`、`application.yaml`、`RabbitSemanticConfig`、`SemanticScanProducer/Consumer/Message`、`SemanticMemoryService(Impl)`、`ChatSummaryService`、`ChatStreamService`(注入点/路由)、`AssistantConfig`、`IntentRouter`、`HabitExtractor`、`JacksonObjectMapper`、`WebMvcConfiguration`、`CoreChatPrompt.txt`
- 服务端：容器内实读 `ingest.py`、`retrieve.py`、`dto/common.py`、`dto/retrieve.py`、`zep_graphiti.py:get_fact_result_from_edge` + 实调 `/openapi.json`、`/search`
- 端口/网络：`192.168.88.130` 上 Graphiti `8088`、Neo4j `7474/7687`、RabbitMQ `5672` 均可达
