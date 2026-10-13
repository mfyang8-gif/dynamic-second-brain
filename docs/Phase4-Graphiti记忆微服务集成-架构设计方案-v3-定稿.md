# Phase 4 · Graphiti 记忆微服务集成 — 架构设计方案 v3（定稿）

> **状态**：本版为**定稿**，取代 v2（`Phase4-...-v2.md` 保留作九轮决策的过程记录）。
> 九轮决策（P1~P8 + P1-新 + P3-补）已全部拍板；本版同时并入九轮中所做的**自查纠正**（4 处，见 §0.2）。
> 本文档为**实施方案**，在 Review 通过前**不含任何业务实现代码**（接口定义与伪代码除外）。
> 基础设施：Neo4j + Graphiti Python 服务已部署于 `http://192.168.88.130:8088`（镜像 `custom-graphiti:v2`），API 契约已锁定并实测。

---

## 零、决策索引

### 0.1 九轮结论一览（已全部拍板）

| # | 议题 | **定稿结论** | 影响面 |
|---|---|---|---|
| **P1** | Qdrant 是否新建「对话流水账」 | **B —— 新建 `conversation_archive` 集合**，稠密+稀疏 RRF 复用 `QdrantTemplate` | 配置 + 少量代码 |
| **P1-新** | 该集合的召回边界 | **① 只做「引用/回忆」**：仅当命中回忆类意图才召回，回答须带出处；常规提问**不召回** | 意图路由 |
| **P1-附** | `fact.episodes[]` 反查原话 | **保留**（零成本白捡的「记忆溯源」能力） | 前端详情 |
| **P2** | `{{context}}` 类别声明 | **A —— 双点声明**（块首 + System Prompt 来源说明），措辞按用户修正版 + 术语锚点 | `CoreChatPrompt.txt` |
| **P3** | `EPISODIC_MEMORY` 语义 | **必须扩**：「单次会话回溯」→「全局画像 + 跨会话回溯」；前端记忆分类**拍扁**统一展示 | 产品语义 + 前端 |
| **P3-补** | 画像类走意图还是常驻 | **② 不拆意图，画像常驻注入**（见 §0.3 待确认项） | 召回管线 |
| **P4** | 高危事实快通道 | **B —— 规则词表预筛 + 提前投递**（纯规则、零 LLM），`source_description` 写人话式来源说明 | 写入链 |
| **P5a** | `group_id` 命名 | **A —— `user_{userId}` 全局单组** | 契约 |
| **P5b** | 租户隔离防线 | **完全采纳**：服务端推导 + Client 层强制非空断言（空则抛 `IllegalStateException`） | 安全（零信任） |
| **P6a** | 作废事实过滤判据 | **A —— 只判 `invalid_at`**（+ 观测埋点） | Client |
| **P6b** | 过滤开关形态 | **A —— 无全局配置开关**，只做方法级旁路 + **独立返回类型** `RecalledFactWithLifecycle` | Client |
| **P6c** | 作废事实能否进 Prompt | **A —— 彻底不进**常规 Prompt | Prompt |
| **P7a** | 影子期抽样协议 | **A —— 四层抽样**（含抽样观测「被判否」的原文） | 验收 |
| **P7b** | 验收载体 | **A —— 只读对照清单 + 导出脚本**（原话↔fact 并排） | 验收 |
| **P7c** | 上线门限 | **A —— 版本化量化门限 + 样本量口径**（非时间口径） | 验收 |
| **P8a** | 单次投递条数 | **B —— `N=1` 逐条独立投递**（理由见 §3.9，**推翻 v2 的「N=3」**） | 写入链 |
| **P8b** | 投递状态字段 | **B —— 双字段** `dispatched_at` + `synced_at` + 异步对账（**推翻 v2 §3.6 的 S1**） | 写入链 + DDL |

### 0.2 九轮中的四处自查纠正（v2 有误，本版已改）

| # | v2 原文 | **纠正** | 出处 |
|---|---|---|---|
| **纠正 1** | §3.4 结论 3「建议一次 POST ≤ 3 轮」 | **改为 `N=1`**。理由：批在服务端**不产生差异**（`ingest.py` 逐条入队），批的唯一收益是省几次 HTTP 往返（相对 20~45s 处理耗时**可忽略**）；而 `N=1` 让**投递粒度 = 确认粒度 = 状态更新粒度**三者对齐 | §3.9 |
| **纠正 2** | §3.6 S1「消费者内轮询 90s 确认落库」 | **S1 有阻塞副作用，已废弃**。它会占住 MQ 消费者线程（批 10 条 = 15 分钟无法 ack → 触发 RabbitMQ 假死判定）。**改为：投递即 ack + 独立定时对账任务异步确认** | §3.6 |
| **纠正 3** | §4.1(b)「与 `CoreChatPrompt` 第 3 条『禁重复』冲突」 | **引错条款**。第 3 条是「禁止收尾总结」+ 回答内两段间禁重复，与作废事实无关。**真实冲突是核心纪律第 5 条（只能基于检索内容）+ 第 4 条（强制角标、不可禁用）** | §4.1 |
| **纠正 4** | §4.2 注释「固定 query，embedding 天然可缓存」 | **缓存已撤回**（会破坏改口裁决：图谱持续在变，缓存窗口内会把已作废的旧偏好喂给模型）。画像通道改为**模板化 query，不缓存** | §4.2 |

### 0.3 ★ 一处尚待你最终点头的项

**P3-补 的「双通道」修正尚未得到你的明确回复。** 你当时拍板「选 ② + 驳回固定 query，必须拿用户当前 prompt 动态查」，我随后提出**反向论证**（见 §4.2 理由），认为动态 query **单独不够用**，应改为：

| 通道 | query | max_facts | 职责 |
|---|---|---|---|
| ① 画像 | **模板化固定句** | 15 | 捞「与当前问题语义不沾边、但你必须知道」的隐式需求（写代码时也要带上「讨厌注释」） |
| ② 情景 | **用户本轮原话** | 10 | 捞当前话题相关的记忆（问吃啥 → 捞出海鲜过敏） |

**两条并发、均不缓存。** 本版按此写；如你不同意，只需回一句，我改回单通道。

### 0.4 四面一体（本 Phase 的全部工作量）

```
① 契约层   Graphiti Client + DTO + 安全断言（B0）
② 写入链   MQ 两级队列 + 实质判定 + 高危快通道 + 异步对账（B1）
③ 召回链   双通道并行召回 + Prompt 铁律 + 四层优先级（B2）
④ 管理面   遗忘权 + 记忆溯源 + 前端分层展示（B3）
⑤ 影子验收 四层抽样 + 量化门限（B4）
```

---

## 一、架构决断

### 1.1 三层记忆 × 物理映射（铁律）

| 层 | 回答什么 | 物理载体 | 现有代码锚点 | 写入方 | 读取方 |
|---|---|---|---|---|---|
| **L1 短期工作记忆** | 「我刚才那句话是什么意思」「把上一段代码重写」 | Redis（`MessageWindowChatMemory` + `RedisChatMemoryStore`）+ MySQL `chat_message` 原文 | `ChatMemoryProviderConfig`、`buildConversationContext()`、`ChatSummaryService` | 每轮对话落库 | `{{historySummary}}` + `{{recentContext}}` |
| **L2 语义与偏好记忆** | 「我老婆对什么过敏」（精确关系）、「我写代码有什么习惯」（模糊画像） | **Graphiti (Neo4j) — 唯一真相源** | 本 Phase 新建 | MQ `graphiti.write.*` | `{{userGlobalMemory}}` + `{{episodicMemory}}` |
| **L3a 资料与知识库** | 「根据我上传的 PDF 总结」 | Qdrant `dynamic_brain`（**外部资料**，不做裁决） | `QdrantTemplate`、`QdrantConfig` | 文档上传时切片 | `{{context}}` |
| **L3b 情景原话档案** | 「我昨天原话是怎么怼那个产品的」 | Qdrant `conversation_archive`（**对话原话切片**，只读、不做裁决） | 本 Phase 新建（配置驱动建集合） | 对话落库时切片 | 仅回忆类意图 → `{{context}}` 或独立块 |

**为什么 L2 必须是 Graphiti 而不是「Qdrant 也能做向量检索」**：Qdrant 能做相似度，但**没有时间维度**。凡是会随时间改变的主观属性（习惯、偏好、人设），只有 Graphiti 的 `invalid_at` 时间戳能做**裁决**——把旧事实作废、新事实生效，且作废时刻与新事实生效时刻毫秒级一致（已实测复现）。缺了它，新旧偏好并存，模型必然自相矛盾。

**为什么「对话原话」放 Qdrant 不违反铁律**（P1 的核心论证）：

| 对象 | 会随时间改变吗 | 需要裁决吗 | 归属 |
|---|---|---|---|
| 「我以后只用 Go」（**偏好**） | ✅ 会 | ✅ 需要 `invalid_at` | **Graphiti** |
| 「我昨天说过『Java 太啰嗦』」（**原话记录**） | ❌ 永不改变 | ❌ 不需要 | **Qdrant** |

**对话原话是「发生了就永不改变」的教科书定义**——昨天那句话不会因为今天改口而改变。真正违例的是「把**偏好**写进 Qdrant」，那是 `user_semantic_habits`，本次删。

### 1.2 四条禁令

1. **严禁**把任何提炼后的习惯/偏好写入 Qdrant 或 MySQL —— 会丧失时效裁决能力，导致主模型「精神分裂」。
2. **严禁**在 L2 之外存在第二份「用户偏好真相」—— 包括把旧 `user_semantic_memory` 表留作只读并行。（「不搞双轨」的物理含义：不是不建表，而是**不留第二个权威源**。）
3. **严禁** Qdrant 承担任何逻辑裁决 —— 它只负责返回「发生了就永不改变的文本切片」，不带时效语义、不做对错判断。
4. **★（P1-新 新增）严禁**让「历史原话」参与事实性回答 —— `conversation_archive` 只服务于「引用/回忆」类意图。常规提问（「我该用什么框架」）召回历史原话，等同于**人为扩大「过期原话 vs 当前事实」的冲突敞口**。

### 1.3 与代码现状的三处偏差（必须先对齐）

**偏差 A：Qdrant 里从来没有「对话流水账」。**
`spring.data.qdrant.collections` 现有两个集合（实读 `application.yaml`）：

- `dynamic_brain` —— 用户**上传文档**的切片（indexes: `bookId` / `userId` / `pageId` / `createdAt`）
- `user_semantic_habits` —— 习惯语义查重索引（**本次废弃对象**）

所以用户举例中「那句话进了 Qdrant 流水账」**在当前代码里不成立**——那句话实际存在于 MySQL `chat_message`。风险一依然真实，但冲突形态需重新定位（见 §4.1）。**P1 的 `conversation_archive` 是新建集合，不是复用现状。**

**偏差 B：`user_semantic_habits` 这个集合本身违反铁律 1** —— 它按定义就是「习惯的向量副本」。必须随 Phase 3 一并删除。

**偏差 C（新增）：L1 是「条数窗口」不是「时间窗口」。**
`MessageWindowChatMemory.maxMessages = 6`（`chat.memory.max-messages: 6`）→ 密集对话 5 分钟就挤满，所谓「保留 15 分钟」在当前实现下**不成立**。且 `RedisChatMemoryStore` 未显式设 TTL。真正的持久层是 MySQL `chat_message`。→ 见 §4.1 风险二。

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
| 1 | **绝不复用现有 `restTemplate` Bean** | `RestTemplateConfig` 的 read timeout 是 **60s**，会钉死 MQ 消费线程。Graphiti client 必须自建 `RestClient` |
| 2 | **绝不共用全局 `ObjectMapper`** | `JacksonObjectMapper` 被注册为 `@Primary`，把 `LocalDateTime` 钉死为 `yyyy-MM-dd'T'HH:mm:ss`；而 Graphiti 返回 `2026-10-12T09:51:56.345970+00:00`（微秒 + UTC offset）→ **解析必然抛异常**。client 自建 ObjectMapper（读用 `JavaTimeModule` + `OffsetDateTime`，写用 `WRITE_DATES_AS_TIMESTAMPS=false`），与 Web 层彻底解耦 |

**Client Bean 形态（两个 `RestClient`，超时不同）**：写/读走不同 read timeout，见 §2.5。

### 2.2 DTO —— 逐字段对照真实 schema（容器源码实读，非推测）

**写入侧** `POST /messages` ← `AddMessagesRequest {group_id, messages[]}`

```java
record GraphitiWriteRequest(String groupId, List<GraphitiMessage> messages) {}

record GraphitiMessage(
        String content,
        String uuid,              // 可选，我们用来承载确定性 ID（"cm_" + chatMessageId）
        String name,              // 默认 ""
        String roleType,          // Literal: user / assistant / system（必填）
        String role,              // ⚠️ 必填字段（可为 null，但必须出现）
        OffsetDateTime timestamp, // ⚠️ 必须显式传，否则被盖上"处理时刻"
        String sourceDescription  // 默认 ""；我们写"人话式来源说明"（见 §3.3）
) {}
```

**四个字段级陷阱（每个都读源码确认过）**

| 陷阱 | 后果 | 规避 |
|---|---|---|
| `messages[]` 漏包一层 | 422 `{"loc":["body","messages"],"msg":"Field required"}` | 唯一入口构造器强制包装 |
| `role` 不传 | ⚠️ **422** —— 源码 `role: str \| None = Field(description=...)`，**无 default**，pydantic 视作必填（可为 null 但字段必须出现） | DTO **不加** `@JsonInclude(NON_NULL)`，显式传 `"user"` |
| `role_type` 取值越界 | 422 —— 源码 `Literal['user','assistant','system']` | 用 Java 枚举，编译期约束 |
| `timestamp` 不传 | 源码 `default_factory=utc_now` → **历史对话被盖上"处理时刻"**，双时间戳裁决直接失真 | **必须显式传 `chat_message.created_at`**（转 UTC `OffsetDateTime`） |

**检索侧** `POST /search` ← `SearchQuery {group_ids[], query, max_facts=10}` → `SearchResults {facts[]}`

```java
record GraphitiSearchRequest(List<String> groupIds, String query, Integer maxFacts) {}

record GraphitiFact(
        String uuid, String name, String fact,
        OffsetDateTime validAt,       // 可空
        OffsetDateTime invalidAt,     // 可空 ← ★ 唯一的作废判据（见 §2.6）
        OffsetDateTime createdAt,     // ★ 非空必填
        OffsetDateTime expiredAt,     // 可空 ← 系统账本时间，不参与有效性判定
        String sourceNodeUuid, String targetNodeUuid,
        List<String> episodes         // 默认 []；P1-附 的记忆溯源入口
) {}
```

**三个必须知道的字段事实（读 `dto/retrieve.py` 原文）**

1. `group_ids` 是 `list[str] | None = None` —— **可选且默认无过滤**。漏传，或写成单数 `group_id`（字段被静默丢弃）→ **跨租户全局检索且 HTTP 200**，零报错。→ Client 内部**强制断言非空**（P5b）。
2. `FactResult` **没有 `group_id`** —— 客户端无法自证一条 fact 属于谁。→ DTO 干脆不设该字段（避免误导），隔离责任 100% 落在 `group_ids` 上。
3. `created_at` 是**非空必填**，其余三个时间字段可空 → 全部 `OffsetDateTime`。

### 2.3 Client 接口：唯一出口 + 语义化旁路（P6b）

```java
public interface GraphitiMemoryClient {

    /** 唯一召回出口：内部已完成 ① groupIds 非空断言 ② invalidAt 过滤 ③ 消毒 */
    List<RecalledFact> recallActive(String userId, String query, int maxFacts);

    /** 显式命名的旁路：排障 / 审计 / 「我的记忆」页面的「变更历史」 需要看已作废事实 */
    List<RecalledFactWithLifecycle> recallIncludingInvalidated(String userId, String query, int maxFacts);

    GraphitiWriteAck write(String userId, GraphitiMessage message);   // N=1（§3.9）

    List<EpisodeRef> listEpisodes(String userId, int lastN);          // 对账 + 溯源用

    void deleteEdge(String edgeUuid);
    String healthcheck();
}

/** ★ 结构上就没有 invalidAt 字段 —— 类型系统即防呆 */
record RecalledFact(String factId, String statement, OffsetDateTime effectiveAt) {}

/** ★ 旁路专用独立类型 —— 与 RecalledFact 不通用，误用即编译失败 */
record RecalledFactWithLifecycle(
        String factId, String statement,
        OffsetDateTime validAt, OffsetDateTime invalidAt, OffsetDateTime expiredAt,
        Lifecycle status              // ACTIVE | SUPERSEDED
) { enum Lifecycle { ACTIVE, SUPERSEDED } }
```

**三条设计铁律**：

1. **`RecalledFact` 在类型上不可能携带作废事实**。过滤发生在「唯一出口」内部，而不是靠每个调用点自觉。
2. **想拿作废事实必须显式调用另一个方法名**——「要打错字才会犯错」就是防呆本身。
3. **旁路返回独立类型，不是给 `RecalledFact` 加可空字段**。若两者同类型，「类型系统即防呆」会被撤销：拿到 `RecalledFact` 的代码**无法区分**它是过滤过的还是没过滤的。

**为什么不做全局配置开关（`include-invalidated: true`）—— P6b 的三条理由**：

1. **一个影响全局正确性的开关不该存在**。本 Phase 唯一的存在理由就是「改口裁决」。一个能被误开、忘关的全局布尔，等于给「关掉核心能力」留后门——而失效是**静默的**（无异常、无日志、无告警）。
2. **它没有真实消费者**。需要看作废事实的场景只有三个：排障（开发者）、「记忆变更历史」UI（走**独立只读接口**，不经过 `recallActive`）、双时间戳验收（测试）。**三个都是「特定调用点 + 特定用途」，没有一个是「运行时按配置切换」。**
3. **方法名的可读性远胜布尔值**。调 `recallIncludingInvalidated()` 的人必须**打出这个方法名**，是有意识、可 grep 的选择；而 `recallActive()` 配 `include-invalidated: true`，**从调用点代码完全看不出这里会拿到作废事实**。

### 2.4 过滤放在哪一层：**Client/Service 层，不是拦截器层**

| 方案 | 判定 |
|---|---|
| HTTP 拦截器里过滤 | ❌ **会产生信息黑洞**：排障要看已作废边、前端「记忆变更历史」要看、双时间戳验收本身要读作废事实 —— 三条路全断。且技术上需 `readTree → 改 JSON → 重序列化`，等于解析两遍 |
| Client 的 `recallActive()` 内部（≈ Service 层语义） | ✅ **定稿**：既收敛默认行为，又不堵死旁路 |

### 2.5 超时分档（读操作必须远短于写操作）

| 调用 | connect | read | 依据 |
|---|---|---|---|
| 检索（在线路径，阻塞 SSE 首字） | 1s | **2s** | 有硬预算，超时即降级为「无记忆」 |
| 写入（MQ 路径，不阻塞用户） | 2s | **5s** | 只要 202 到手即可 |
| 对账轮询（定时任务，不阻塞任何用户路径） | 1s | 3s | 短，可多次 |

### 2.6 ★ 作废判据：只判 `invalid_at`（P6a + 纠正）

**结论：判「当前是否有效」只需看 `invalid_at`，不看 `expired_at`。** 三源交叉验证：

| # | 证据 |
|---|---|
| 1 | **官方口径原文**（`graphiti_core/search/search_helpers.py:57`）：`Facts with an **invalid_at** date of "Present" are considered valid` —— `expired_at` 在这句里**根本不出场** |
| 2 | `expired_at` 只有 3 条赋值路径，**每条都紧贴 `invalid_at`**：`utils/maintenance/edge_operations.py:569-570`（被新边作废）、`:822-823`（补账）、`:838-840`（新边被更晚的候选作废） |
| 3 | Neo4j 直查全部 10 条边：`invalid_at` 非空 2 条 / `expired_at` 非空 2 条 / **只 invalid = 0 / 只 expired = 0** |

**两者语义确实不同**（实测差 14~16 秒）：

- `invalid_at` = 事实**业务上失效**的时刻（可能远早于系统处理时刻）→ **「当前是否有效」和「你何时改的口」两个问题，答案都在它身上**
- `expired_at` = 图里**登记为失效**的系统账本时刻

**为什么不用 `invalid_at OR expired_at`**：① 贴官方口径；② `expired_at` 是系统账本，不表达业务真伪，用它过滤会在「补录历史消息」场景（`invalid_at` 是业务时间、`expired_at` 是补录时刻）把两件事混起来；③ 多一个 OR = 多一个「将来某条路径改了语义就静默变严」的隐患。

**风险与缓解**：若 Graphiti 将来新增「只设 `expired_at`」的路径，只判 `invalid_at` 会漏。→ **缓解不是提前把 OR 塞进去，而是加观测点**：`recallActive()` 内若发现 `invalidAt == null && expiredAt != null`，打 WARN 日志。**让事实先暴露，再决定要不要改判据。**

⚠️ **上游文档 bug（必须写进实现陷阱清单）**：`graphiti_core/edges.py:271` 把 `expired_at` 的描述写成 `'datetime of when the **node** was invalidated'` —— 复制粘贴错误（明明是 edge），措辞还与 `invalid_at` 的 `'when the fact stopped being true'` 高度混淆。**照字段描述写代码的人，都会误以为 `expired_at` 才是作废标记。**

---

## 三、第二部分 · 基于 RabbitMQ 的异步写入设计

### 3.1 现有链路锚点 + 三个必须先修的隐患

**现有链路骨架极好，直接复用**：
`ChatStreamService.armSessionScan()` →（落库成功后）Redis `setnx semantic:scan:armed:{sessionId}` TTL 5min（**冷却锁**：把「N 轮对话 N 次 LLM 分析」收敛为「会话静默 5 分钟后仅扫一次」）→ 投 `semantic.scan.delay.queue`（队列级 TTL 300_000）→ 过期死信到 `semantic.scan.exchange` → `semantic.scan.queue` → `SemanticScanConsumer.onScanMessage()` → 取最近 10 条 user 轮次 → `HabitExtractor` → `SemanticMemoryServiceImpl.processAndPromote()`。

**三个隐患必须先修**（否则新链路会把它们放大成静默丢数据）：

| # | 隐患 | 后果 |
|---|---|---|
| 1 | `semantic.scan.queue` **未声明 DLX**，且全局 `default-requeue-rejected: false` | 消费抛异常 = **静默丢弃且不重投**，队列看起来「正常」 |
| 2 | Consumer 是 catch-all 吞异常 | **完全没重试** |
| 3 | `armed` 锁在 `finally` 里**无条件** `releaseArmLock()` | 本轮消费失败时，丢失的那一轮不会被补扫（注释写的「下次会话自然重扫」只在用户继续说话时成立） |

### 3.2 新链路拓扑（两级队列 + 唯一写入口）

```
落库成功 ──► armSessionScan()          [冷却锁语义不变，仍为 5min setnx]
                 │
                 ▼  默认交换机("")，rk = 队列名
     graphiti.scan.delay.queue         队列级 TTL 300s, DLX = graphiti.scan.dlx
                 │ 过期
                 ▼
     graphiti.scan.dlx ──[rk=scan]──► graphiti.scan.queue
                                            │
                                    GraphitiScanConsumer (concurrency=1)
                                    ① 幂等门  ② 按水位取窗口  ③ 逐轮过 MemorySignalJudge
                                    ④ 通过的轮次 → **逐条**投 graphiti.write.queue（N=1）
                                            │
高危词表命中（对话结束时立即投递，绕过 5 分钟）─┤
                                            ▼
                                  graphiti.write.queue    ← ★ 唯一 Graphiti 写入口
                                            │
                                    GraphitiWriteConsumer (concurrency=1, prefetch=1)
                                    ① 幂等门(Redis setnx dedup)  ② POST /messages（单条）
                                    ③ 收 202 → UPDATE dispatched_at → 立即 ack
                                            │
        ┌───────────────┬───────────────────┴──────────────┐
        ▼               ▼                                  ▼
     成功 ack     可重试失败                          毒消息(400/422)
              → graphiti.write.retry.30s.queue              │
                 (TTL 30s, DLX → write.queue)               ▼
                 ├→ graphiti.write.retry.2min.queue    graphiti.write.dlq
                 └→ graphiti.write.retry.10min.queue

【独立，与上述链路解耦】GraphitiConfirmJob（定时对账，每 5 分钟）
   扫 chat_message WHERE synced_at IS NULL AND (dispatched_at IS NULL OR dispatched_at < now-3min)
   → GET /episodes/{groupId}?last_n=K 按 uuid 比对
   → 命中 → UPDATE synced_at ；连续超时 → 重投（dispatched_at 置 NULL → 投 write.queue）
```

**四个关键设计点**：

1. **`graphiti.write.queue` 是唯一 Graphiti 写入口**。常规路径（scan 消费者逐条投）与高危快通道（直接投）**汇入同一个队列、同一个消费者** → 投递状态写入逻辑只有一处，重试/DLQ 只有一套。
2. **`N=1` 是投递粒度**（P8a）：scan 消费者发现 N 条待投时**循环投 N 次**，而不是一次 POST 带 N 条。理由见 §3.9。
3. **重试队列必须是 3 个「静态 TTL」队列**：RabbitMQ 队列 TTL 是**队列级**属性，队首消息的 TTL 会挡住后面的短 TTL 消息（**队头阻塞**）。单队列 + 多档 TTL 是错的。
4. **`concurrency=1 / prefetch=1` 不是保守，是必须**：Graphiti 侧 `AsyncWorker` 是**单协程串行**消费 `asyncio.Queue`，压再多并发只是把排队搬到服务端，而我们这边反而多占线程。
5. ⚠️ **新消息类必须登记进 `RabbitSemanticConfig.jacksonMessageConverter()` 的 `addTrustedPackages`** —— 白名单是**包名精确 `equals`**（源码注释已有血泪记录：只写父包不生效，表现为「队列正常清空、deliver+1 但 ack 恒为 0、消费者方法体从不执行」）。若新类放在 `com.yangmf.mini_nodepad.mq` 则天然已覆盖。

**高危快通道的一处简化建议**：P4 拍的是「独立队列 + 独立 DLX」。经拓扑设计，**快通道只需直接投 `graphiti.write.queue`**（它就是 delay 队列过期后的落点），**不需要新队列** —— 省一整套拓扑，且天然复用幂等与重试。若将来需要「快通道抢占常规消息」，再拆独立优先级队列。**（待你确认）**

### 3.3 消费者流水线

**GraphitiScanConsumer（发现层）**

```
① 幂等门    Redis setnx(graphiti:scan:dedup:{sessionId}:{watermark}, TTL 1h)
② 取窗口    按 sessionId 取 [水位, now] 之间的 user 轮次（MySQL chat_message；
            @TableLogic 自动排除 regenerate 软删；★ 只取 user —— 沿用既有裁决：
            "AI 回答中的客串假设句会诱导抽取器产生自我强化幻觉"）
③ 实质判定   ★风险三  逐轮过 MemorySignalJudge（二值），只保留"含用户属性/事实"的轮次
            ⚠️ 判否的轮次必须落【审计表】—— 这是 P7 层 4 抽样唯一的数据来源（见 §5.3）
④ 逐条投递  每个通过的轮次 → 一条 GraphitiWriteMessage → graphiti.write.queue
            uuid = "cm_" + chatMessageId；timestamp = chat_message.created_at
⑤ 水位推进  Redis SET graphiti:scan:watermark:{sessionId} = 本轮最大 created_at
```

**第 ⑤ 步是新增的结构性修复**：有了水位，即使某一轮漏扫，下一轮也能从水位继续 —— 而不是只靠「用户恰好又说话了」。这是对隐患 3 的根治。

**GraphitiWriteConsumer（投递层，N=1）**

```
① 幂等门    Redis setnx(graphiti:write:dedup:{chatMessageId}, TTL 1h)   [> 30s+2min+10min 最大重试窗]
② 投递      POST /messages { group_id: GroupIdResolver.of(userId),
                             messages: [ 单条 ] }        ← ★ N=1
③ 收 202    → UPDATE chat_message SET graphiti_dispatched_at = now()
                         WHERE id = ? AND graphiti_dispatched_at IS NULL
            → 立即 ack（不等待落库确认；确认由 GraphitiConfirmJob 异步做）
④ 异常分类  400/422 → 毒消息 → graphiti.write.dlq（不重试）
            连接拒绝/超时/5xx → 按 attempt 投对应重试队列（30s / 2min / 10min）
```

**`source_description` 的正确用法（P4 定稿）**：

- **常规投递**：写**会话来源**，如 `"session:S123 5min-batch"`
- **高危快通道**：写**人话式来源说明**，如 `"用户消息（命中高危词：过敏，提前投递）"`

⚠️ **不要**写成纯技术标记（如 `【高危预警触发】`）—— 该字段**不进** `episode_body`，但它是**给人和前端看**的语义字段（库里实测值为 `"验收-初始偏好"` / `"验收-改口"`），塞技术标记会污染语义。人话式写法保留全部审计能力，还顺带记录了**是哪个词命中的**。`name` 字段实测全为空串、完全空闲，是备选载体。

⚠️ `source_description` **是否参与 LLM 抽取上下文未实测** → B1 期用「同内容、不同 source_description」两条 episode 做对照实验确认。若参与，则**严禁**往里放任何可能被误抽成事实的文本。

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

**每条 message 独立成 episode，各自带自己的 `uuid` 与 `reference_time`。** 由此推出五条结论：

1. ✅ **双时间戳裁决的粒度 = 单条消息** —— 「先 React 后 Vue3」分两条投递即可被正确裁决，**前提是 `timestamp` 必须显式传**。
2. ✅ `uuid` 由我们指定 → **幂等有抓手**。
3. ✅ **一次 POST 带 N 条 = N 次串行 `add_episode`**（单协程 worker），每条 20~45s（实测）→ 见 §3.9 的 `N=1` 决策。
4. ✅ **批内消息顺序不影响裁决结果**（源码推理，B1 验证）：`resolve_edge_contradictions`（`edge_operations.py:544-573`）比的是 **`valid_at`**；`edge_operations.py:838-840` 的判断条件也是 `candidate_valid_at_utc > resolved_edge_valid_at_utc`。**`valid_at` 更晚的那条恒胜，与处理顺序无关。**
5. → **写入粒度确定：一轮 = 一条 message，只投 user。** 不做「整批合成一个 episode」—— 那会让同一批次内的改口失去裁决依据。

### 3.5 幂等（两层）

- **第一层：`uuid` 承载确定性 ID**（`"cm_" + chatMessageId`）。
  ⚠️ **尚未验证**：`add_episode(uuid=...)` 在 uuid 已存在时是**抛错 / 覆盖 / 照旧重复建边**？→ **B1 的第一个验证项**（同一 uuid 投两次，观察 `/episodes` 与 `/search` 的差异）。
  ★ **不要指望 uuid 幂等能去重「边」** —— episode 节点可能 MERGE，但边不保证去重。真正的防重靠 `graphiti_dispatched_at`（§3.8）。
- **第二层：Redis `setnx`**（沿用 `SemanticScanConsumer:65-66` 现成套路），TTL 1h 覆盖最大重试窗。

### 3.6 ★ 落库确认：**202 ≠ 成功**，且**确认不能阻塞消费者**（纠正 v2 的 S1）

`POST /messages` 返回的 202 只代表「进了 `asyncio.Queue`」。首条 episode 实测 **21~45s** 才进 Neo4j。

| 策略 | 评价 |
|---|---|
| S0 收到 202 就 ack（不确认） | ❌ 后台 worker 失败时我们**完全不知道** → 重试机制形同虚设 |
| ~~S1 消费者内轮询 `GET /episodes` 按 uuid 比对~~ | ❌ **v2 的 S1 已废弃** —— 见下方「为什么废弃」 |
| **S2 投递即 ack + 独立定时对账（定稿）** | ✅ **推荐** |
| S3 回调 | ❌ Graphiti 无 webhook 能力 |

**为什么废弃 v2 的 S1**：S1 在**消费者线程内**轮询（90s 窗口、5s 间隔）。即使 `N=1`，每条也要阻塞最多 90s；而一次扫描可能有 10 条待投 → **消费者被占住 15 分钟无法 ack** → RabbitMQ 会认为消费者假死、`consumer_timeout` 可能直接杀连接。**这是不能接受的。**

**S2 的完整形态**：

```
MQ 消费者：POST /messages → 收 202 → UPDATE dispatched_at → 立即 ack（耗时 <1s，永不阻塞）
GraphitiConfirmJob（每 5 分钟）：
  ① 扫 chat_message WHERE synced_at IS NULL
       AND (dispatched_at IS NULL OR dispatched_at < now - 3min)     ← 覆盖确认窗
  ② GET /episodes/{groupId}?last_n=K → 按 uuid 比对
  ③ 命中 → UPDATE synced_at = now
  ④ 未命中且 dispatched_at 已超时（> 5min）→ 重投（dispatched_at 置 NULL + 投 write.queue）
  ⑤ 连续 N 轮未确认 → 记 WARN + 计数（喂 P7 的健康度指标）
```

**S2 的三个附带收益**：① 消费者永不阻塞；② 重试发生在对账任务里，消费者线程干净；③ `dispatched_at`/`synced_at` 是 MySQL 里的**审计事实**，一条 SQL 就能回答「哪些投了没落库」——直接喂 P7 的 DLQ/健康度监控。

⚠️ `/episodes` 返回的是 graphiti-core 原始 `EpisodicNode` 列表（**不是 DTO**）。已实测：返回体含 `content` 原文 + `valid_at` + `group_id` + `entity_edges`，时间格式是 `...Z`（**与 `/search` 的 `+00:00` 不同**，`OffsetDateTime` 两者都能吃）。⚠️ 但 `last_n` 是**倒序截断，无游标**，只能确认最近投递的 uuid。→ **B1 验证项**：确认返回体里 uuid 字段的确切名字。

### 3.7 重试分类（决定退避曲线）

| 情况 | 判定 | 动作 |
|---|---|---|
| 400 / 422 | **毒消息**（载荷错误，重试无意义） | 直接进 DLQ，**不重试** |
| 连接拒绝 / 超时 / 5xx / 502 | 可重试 | 30s → 2min → 10min，三次后 DLQ |
| 对账确认超时 | 可重试（不是毒消息） | 由 `GraphitiConfirmJob` 重投 |

**重试计数承载方式**：消息体加 `attempt`（int），消费者按 `attempt` 决定投哪个重试队列；`attempt >= 3` → DLQ。

★ **不要用 `@Retryable` 做 MQ 重试**：它在**同一线程内循环**，重试期间消息未 ack、消费者被占住；而 TTL+DLX 把重试**卸载到 broker**，消费者线程立即释放。两者语义不同，别混用。（`@EnableRetry` 在工程里已开，但**不要用在这条链上**。）

### 3.8 ★ 投递状态：双字段状态机（P8b）

**为什么必须双字段** —— `synced_at` 的写入时机是个二选一困境：

| 时机 | 后果 |
|---|---|
| **收到 202 就写 `synced_at`** | worker 后续静默失败 → 该消息永远不会被重投，**永久丢失** |
| **确认落库后才写 `synced_at`** | 20~45s（甚至 90s+）的确认期内它仍是「未同步」 → **下一轮扫描会重复捞到它** → 重复投递 |

**定稿：两个字段，三段状态**

| 状态 | `dispatched_at` | `synced_at` | 含义 |
|---|---|---|---|
| **未投递** | NULL | NULL | 待扫描投递 |
| **投递中** | 非空（< 3min） | NULL | 已交 Graphiti，等落库；**扫描/对账都不许重投** |
| **已落库** | 非空 | 非空 | 终态 |
| （例外）**投递超时** | 非空（> 3min） | NULL | 对账任务判定失败 → 重投（`dispatched_at` 置 NULL） |

```sql
ALTER TABLE chat_message
  ADD COLUMN graphiti_dispatched_at DATETIME NULL COMMENT '投递到 Graphiti 的时刻（收到 202 后写入）',
  ADD COLUMN graphiti_synced_at     DATETIME NULL COMMENT '确认落库到图谱的时刻（对账任务写入）',
  ADD INDEX idx_graphiti_pending (graphiti_synced_at, graphiti_dispatched_at);
```

**为什么不用 Redis 锁替代**：Redis 不是权威源（重启/淘汰就丢），而「投递状态」是写链路的**审计事实**，应该落在 MySQL 里（可 SQL 直查、可补投、可审计）。

**为什么不用 `graphiti_synced_at` 单字段 + Redis `setnx` 防重捞**：单靠 Redis 锁，一旦锁丢失就会重复捞取；双字段是**幂等的、可自愈的**（`dispatched_at` 超时自动放开），不依赖任何易失状态。

⚠️ **连带修改**：`fetchRecentUserTurns()` 的 WHERE 必须同步改为 `graphiti_synced_at IS NULL AND (graphiti_dispatched_at IS NULL OR graphiti_dispatched_at < now - 3min)`（否则 `N=1` + 单字段会重复捞取）。

### 3.9 ★ 投递条数：`N=1` 逐条投递（P8a，推翻 v2 的 N=3）

**先拆掉「批次上限」这个问题的前提**：因为 `ingest.py` 是**逐条入队**（§3.4），

> **一次 POST 带 3 条 = 服务端入队 3 个独立 job。**
> 那么「批 3 条」与「单条投递 3 次」在**图谱结果上完全等价**。批的**唯一**作用是**少几次 HTTP 往返**。

**所以批大小不是正确性参数，只是「HTTP 往返次数 vs 投递语义清晰度」的取舍。**

**真实约束在速率账上**（单协程 20~45s/条，触发周期 300s）：

| N | 消费耗时 | 相对触发周期的余量 | 判定 |
|---|---|---|---|
| 1 | 30s | **10×** | ✅ 定稿 |
| 3 | 90s | 3.3× | ✅ 可行 |
| 5 | 150s | 2× | ⚠️ 勉强 |
| 10 | 300s | **零余量** | ❌ 一旦某条慢到 45s 就永久积压 |

**定稿 `N=1`，四条理由**：

1. **批不产生服务端差异** → 批的唯一收益（省几次几十 ms 的 HTTP 往返）相对 20~45s 处理耗时**可以忽略**；
2. **投递粒度 = 确认粒度 = `graphiti_dispatched_at` 更新粒度**，三者天然对齐，不需要回答「批内部分成功怎么算」这个额外问题；
3. **失败隔离最彻底**（一条毒消息不牵连其它）——「整批 202」会让投递层误以为全部成功；
4. **顺序无关已被论证**（§3.4 结论 4），所以批连「保序」这个好处都不提供。

**唯一代价**：投递次数 ×3。但投递是毫秒级的（202 立即返回），且已与确认解耦，**不构成瓶颈**。

---

## 四、第三部分 · 三层召回与 Prompt 注入管线（The Interceptor）

### 4.1 三个风险的专项防御

#### 风险一：记忆源「神仙打架」

**先修正冲突的真实形态**（依据 §1.3 偏差 A）：Qdrant 里原本没有对话流水账，所以真实冲突是：

| 冲突 | 场景 | 现有机制能否处理 |
|---|---|---|
| **① 笔记/资料 内容 vs 图谱事实** | 用户笔记或检回的资料里写着「Java」，而图谱当前有效偏好是「只用 Go」 | ❌ **完全没处理，且长期存在** |
| **② 历史原文 vs 图谱事实** | L1 窗口里还留着用户一个月前说过、但已被图谱作废的话 | 部分（窗口只有 6 条，通常已滚出） |
| **③ 图谱内部新旧** | 用户改口 | ✅ Graphiti 已解决 |
| **④（P1 新增）历史原话档案 vs 图谱事实** | 回忆类意图召回了过期原话 | ✅ 由铁律第 3 层 + P1-新 的意图隔离共同处理 |

**防御 = 三重，绝不只是 Prompt**：

**(a) 结构性防御（首选，先于 Prompt）—— 物理分区注入，让模型不需要「猜」哪条更权威**

- `{{userGlobalMemory}}` ← **只有** Graphiti 过滤后的当前有效偏好/禁忌（XML 隔离，沿用现有 `<user_preferences>` / `<forbidden_rules>` 形态）
- `{{episodicMemory}}` ← **只有** Graphiti 的情景关系事实（带「生效于 YYYY-MM-DD」标注）
- `{{context}}` ← 笔记切片与全网引用，**块首新增声明**（见下）

**(b) `{{context}}` 块首声明（P2 定稿措辞 —— 用户修正版 + 术语锚点）**

```
【外部资料与历史记录】以下内容包含检索到的参考资料或过往历史。如果其中内容与用户的
「当前生效偏好与画像」发生任何冲突，必须无条件以「当前生效偏好与画像」为准。不要把
其中的历史情绪或客观描述误认为用户当前的直接指令。
```

**三个必须避开的措辞陷阱**：

| 陷阱 | 错误写法 | 为什么错 |
|---|---|---|
| **一刀切否定** | 「这些**不代表用户本人的事实陈述**」 | 用户上传的《我的简历.pdf》《忌口清单.docx》会被这句否定 → 问「根据我的简历，我做过什么项目」时模型答「资料显示有人做过 XX 项目，但不代表您」——**功能全面瘫痪** |
| **术语悬空** | 只说「以当前生效偏好为准」，但没说它指哪块 | 模型**不知道这个词指向哪块**。→ **必须让记忆块用同一个词自称**：`{{userGlobalMemory}}` / `{{episodicMemory}}` 块首用 `【当前生效偏好与画像】` |
| **维度混淆** | 与既有【来源权威性裁决】第 3 条（本地笔记 vs 全网冲突 → 以全网为准）不区分 | 那条是**事实时效**维度，本条是**用户属性**维度。同在 Prompt 里，模型极易混用 → 必须显式切分 |

**(c) System Prompt 记忆权重铁律（写成 `CoreChatPrompt.txt` 的固定文本，不做成变量）**

```
【记忆权重铁律 — 不可僭越】
你的上下文中可能出现四类关于用户的信息，权威性从高到低严格如下：

1. 【短期上下文】权威最高 —— 用户刚刚说的、本轮明确的指令。
   它与任何其他来源冲突时，无条件以它为准（用户就是改主意了）。

2. 【当前生效偏好与画像】次之 —— 从历史对话提炼、且已被时效裁决为"仍然生效"的稳定属性。
   它与第 3、4 类冲突时，无条件服从它。

3. 【历史原话】再次之 —— 用户过去说过的话的原始记录（按时间倒排）。
   它记录的是"当时说过什么"，不代表现在仍然成立；不得当作当前事实使用。

4. 【外部资料与历史记录】最低 —— 检索到的参考资料、联网结果与用户保存的笔记。
   若其中内容与【当前生效偏好与画像】发生任何冲突，必须无条件以【当前生效偏好与画像】为准。
   不要将其中的历史情绪或客观描述误认为用户当前的直接指令。

★ 跨类裁决示例：资料里写着"精通 Java"，而【当前生效偏好与画像】显示"当前只用 Go"
  → 必须按 Go 作答，不得把资料里的技术栈描述成用户的个人偏好。
★ 维度切分：【事实性】冲突（如"某个库的最新版本"）按【来源权威性裁决】处理；
  【关于用户本人属性】的冲突按本条处理。两条规则处理不同维度，不要混用。
```

**(d) 物理降噪（配合 P1-新 / P3-补）**：**回忆类**意图才召回 `conversation_archive`；**画像类**问句（「我平时有什么习惯 / 我对什么过敏」）**跳过 Qdrant 检索**。两处都由意图路由收口。

#### 风险二：「5 分钟失忆症」

**先纠正认知偏差**（见 §1.3 偏差 C）：L1 是**条数窗口**（6 条）不是时间窗口，所以「保留 15 分钟」不成立；Redis 未显式设 TTL；真正永不丢的持久层是 MySQL `chat_message`。

**新事实的「可检索延迟」实测账**：5 分钟冷却 + `add_episode` 20~45s ≈ **5.5~5.75 分钟**。

**兜底 = 三件事（按优先级）**：

1. **窗口扩容**：`chat.memory.max-messages: 6 → 20`（≈10 轮），并**实测/显式设置** `RedisChatMemoryStore` 的 TTL ≥ 30min。**最小改动、收益最大的一条。**
2. **加一道「未沉淀原文」时间窗**（结构兜底；条数窗口天然不可靠）：召回时额外从 `chat_message` 取 `created_at >= now - graphiti.recall.unsettled-window-minutes（默认 10）` 的轮次（上限 20 条），作为 `{{recentContext}}` 的独立补充段，块首标注 `[尚未沉淀为长期记忆的近期对话]`。
   → **这一段的语义正好补上「图谱真空期」**：哪怕 Graphiti 里什么都没有，用户 2 分钟前说的「我对海鲜过敏」也一定在。
   ⚠️ 但它只覆盖 10 分钟，**隔夜场景兜不住** → 所以才需要第 3 条。
3. **★ 高危快通道（P4 定稿：B 方案）—— 补的是「时效」，不是「正确性」**

| 项 | 设计 |
|---|---|
| **时机** | `ChatStreamService.persistMessages()` **之后**（对话结束时，**不是「落库后」**） |
| **动作** | 对本轮 **user 原文**跑一次**纯规则**扫描（词表：`过敏 / 忌口 / 不能吃 / 禁忌 / 禁止 / 别给我 / 千万别 / 医生 / 药 / 孕期 …`） |
| **命中** | **立即投递**一条到 `graphiti.write.queue`（绕过 5 分钟 delay 队列），`source_description` 写 `用户消息（命中高危词：<词>，提前投递）` |
| **未命中** | 走原 5 分钟链路 |
| **成本** | 一个词表 + 一次 MQ 投递（毫秒级），**零 LLM 调用** |

**为什么不用 LLM 判定器**：放同步 = 增加首字延迟（最敏感的指标）；放异步 = 又变成「5 分钟后」，毫无收益。

**为什么词表够用**：高危事实（过敏/禁忌/健康）天然带强关键词，不需要语义判断。**收益集中在高危类，成本也由高危类独享。** 普通偏好错过 5 分钟无所谓。

⚠️ **快通道引入了「必然的重复投递」**，这就是 `graphiti_dispatched_at`（§3.8）存在的**第二个理由**：T+0 快通道投一次、T+5min 常规扫描会再从「最近 10 条」里捞到同一条。防重靠 `WHERE graphiti_dispatched_at IS NULL` 落地。

#### 风险三：Graphiti 的「过度提炼」与「幻觉」

**根因**：抽取完全交给 `qwen-plus`，它会把玩笑当事实（「我是狗」→ `(User)-[IS_A]->(Dog)`）、把零碎当实体。质量底线已确认：`structured_output_mode=json_schema` + `qwen-plus`（qwen-turbo 会退化甚至出现 `valid_at == invalid_at` 的自相矛盾，实测过）。

**四道闸**：

1. **前置实质判定**：新增 `MemorySignalJudge`（二值：本轮是否包含「关于用户的、可长期成立的客观事实/属性/偏好」）；`qwen-turbo` + `temperature=0` + **few-shot**（教训已刻在 `IntentRouter` 注释里：qwen-turbo 弱，光给规则不够，必须喂例子）。
   ⚠️ 判定器本身是「漏检即失忆」的风险点 → **宁松勿严**（只滤纯闲聊），并提供 `graphiti.write.judge-enabled: false` 一键关断（关断 = 全量投递）。
   输入用**本轮 user 原文**（不是整批摘要）—— 粒度越细、判定越准、越省 token。
   ★ **判否的轮次必须落审计表**（P7 层 4 的唯一数据来源，见 §5.3）。
2. **投递形态本身就是闸**：`ingest.py` 的 `episode_body` 是 `role(role_type): content`，**assistant 内容不进图谱**（沿用既有裁决，避免 AI 的客串假设句被抽成用户事实）。
3. **注入侧二次消毒**：复用现成的 `PromptSanitizer.sanitizeFact()`（Phase 3 的「记忆投毒三道闸」）。Graphiti 的 fact 同样是 LLM 产物、同样属不可信输入 —— 这条现在**比 Phase 3 更必要**。
4. **事后清洗能力**：`GET /entity-edge/{uuid}` 取单条详情、`DELETE /entity-edge/{uuid}` 删单条、`DELETE /episode/{uuid}` 按 episode 删、`DELETE /group/{group_id}` 按组清空 —— 四者构成事后清洗闭环。一期先跑**影子模式**（§五）人工抽检。

### 4.2 召回编排（双通道并行，硬预算 800ms）

> ⚠️ **本节的「双通道」是 §0.3 的待确认项。**

```
用户提问
  │
  ├─(已有) L1  Redis 窗口原文 + ChatSummaryService 摘要            ── 0 网络开销
  │
  ├─(新增) L2-a 画像召回  POST /search {query: <模板化固定句，见下>,
  │                                     group_ids: ["user_{id}"], max_facts: 15}
  ├─(新增) L2-b 情景召回  POST /search {query: <用户本轮原话>,
  │                                     group_ids: ["user_{id}"], max_facts: 10}
  │        两路并行发起；硬预算 800ms；超时 → 该路视为空（绝不阻塞首字）
  │        ★ 两路均【不缓存】（见下「为什么撤回缓存」）
  │
  ├─(新增) L2-c 回忆召回（P1-新，仅当意图 = 回忆类）
  │        Qdrant conversation_archive 检索（稠密+稀疏 RRF）；命中的原话
  │        进入独立块，带出处与时间，标注「历史原话，不代表当前事实」
  │
  └─(已有) L3  Qdrant dynamic_brain 双路并发（本地知识库 / 全网）  ── 已有 4s 就绪预算
  │
  ▼ Java 侧统一收口（GraphitiMemoryRecaller）
    两路 facts 合并 → uuid 去重 →（invalidAt 过滤已在 Client 内完成）→ sanitizeFact
    → 合并后按 validAt 倒排 → 客户端二次裁剪（各 Top-5）→ 分槽位
  │
  ▼ 组装 System Prompt
    {{userGlobalMemory}}  ← 画像桶（块首【当前生效偏好与画像】）
    {{episodicMemory}}    ← 情景桶（带「生效于 YYYY-MM-DD」）
    {{historySummary}}    ← L1 摘要（已有，不动）
    {{recentContext}}     ← L1 原文 + 「未沉淀原文」时间窗（风险二·第 2 条）
    {{context}}           ← L3 资料（已有，块首加声明）
```

**画像通道的 query 模板**（模板化，不是单个固定句）：

```
用户的技术栈与工具偏好；用户的禁忌、雷区与个人情况（如过敏、身体状况）；用户的沟通与协作习惯
```

**为什么必须是两个通道（P3-补 的反向论证）**：

你说「必须每次拿用户当前原话去动态检索」——它能捞到**情景相关**记忆（「晚上吃啥」→ 捞出「海鲜过敏」），成立。

**但它单独不够用**：用户说「帮我写个贪吃蛇代码」时，这句 query 与「该用户强制要求 Vue3」在语义上**不接近**，搜 Graphiti **不一定能命中 Vue3 那条边**（会命中「喜欢游戏」之类）。而这恰恰是「常驻注入」要解决的隐式需求。

> **「和当前问题语义不沾边、但你必须知道」的那部分，动态 query 天生捞不到。**
> 画像通道的独立职责就在这里。

**为什么撤回「固定 query 缓存」**：

缓存会**正面破坏 Graphiti 的核心能力**。写入链路是「会话静默 5 分钟 + 落库 20~45s」，图谱**持续在变**。任何跨请求缓存都会产生一个窗口：**新事实已落库、缓存还在返回旧值**。而在「用户刚改口」这个**恰好是 Graphiti 唯一存在理由的场景**上，缓存会把**已被 `invalid_at` 作废的旧偏好**喂给模型。

→ **「缓存」不是「短视」，是在架构上自相矛盾**：省一次检索，废掉改口裁决。**两条通道都实时打，都不缓存。**

⚠️ **一个真实风险要提前说**：这样每轮是 **2 次 `/search` 并发**。Graphiti 的 cosine 是逐条算的（Neo4j 无向量索引，已实测），图谱变大后**单次延迟会涨**。→ **一期就 2 条通道封顶**，别再加（画像扩成 3 条 query 的方案留到二期看数据）。

### 4.3 伪代码（注入侧，仅示意，不含实现）

```java
private String buildUserGlobalMemory(Long userId) {   // 替换现有 buildUserMemoryBlock
    if (!graphitiProperties.isEnabled() || !graphitiProperties.getRecall().isEnabled()) {
        return "";                                    // 影子模式 / 总闸关闭
    }
    try {
        List<RecalledFact> facts = graphitiMemoryRecaller.recallPortrait(userId); // 内部已过滤+消毒+裁剪
        return renderPreferenceBlock(facts);          // 空则返回 ""（零污染，沿用现有约定）
    } catch (Exception e) {
        // 铁律：注入侧任何异常都不得向上抛（主流程绝不为记忆失败而失败）
        log.warn("长期画像召回失败，降级为无记忆对话 | userId={}", userId, e);
        sink.next(statusEvent("memory_degraded", "长期记忆暂不可用，本轮不参考历史偏好")); // 非阻断
        metrics.counter("graphiti.recall.fail").increment();
        return "";
    }
}
```

**降级姿态（P8 前的确认项）：静默降级（`catch → ""`）+ 一条不打断生成的 notice 级 `statusEvent`。**
纯静默用户会困惑「它怎么又不记得了」；纯报错会让人以为整体挂了。折中：**降级注入但发 notice**，显不显示由前端定。前端已有 `retrieving_memory` 事件位，需新增「失败态」语义。

### 4.4 Prompt 预算分配（防止把主模型撑爆）

| 块 | 预算 | 依据 |
|---|---|---|
| `{{userGlobalMemory}}` | ≤ 300 字 | 画像事实本来就短（Top-5 × ~30 字 + 块首声明） |
| `{{episodicMemory}}` | ≤ 400 字 | Top-5 |
| `{{recentContext}}` | ≤ 800 字 | 20 条 × 单条截断（现有 `buildConversationContext` 已有 150 字截断逻辑可复用） |
| 回忆块（`conversation_archive`） | ≤ 300 字，且**仅回忆类意图出现** | 低频 |
| `{{context}}` | 已有逻辑 | 不动 |

总新增 ≤ **700 字**（≈1k token），相对 `max-tokens: 8192` 与已有多路上下文属可接受增量。
超预算时按 **「情景桶 → 画像桶」** 顺序从尾部截断（画像比情景更稳定，优先保留）。

---

## 五、灰度与影子期验收（P7 定稿）

### 5.1 三个开关分离

`graphiti.enabled`（总闸）/ `graphiti.write.enabled` / `graphiti.recall.enabled`
→ 支持「**只写不读**」的影子模式，也支持任意一侧单独回滚。

**影子模式的必要性**：`qwen-plus` + DashScope + `json_schema` 这个组合**只实测过「改口」一个场景**；实体对齐、跨会话去重、长会话下的图谱质量全未验证。而「记忆污染」是**很难察觉、很难回滚**的失败模式 —— 宁可先只建图不注入。

### 5.2 ★ 影子期的两个盲区（不先点破，任何抽检协议都会落空）

**盲区一：幸存者偏差 —— 只能看见「进来的」，看不见「被挡住的」**

三段漏斗：

```
user 原文 ──① 高危词表(规则)──▶ 命中则提前投递
          ──② MemorySignalJudge(LLM 二值)──▶ 判否 = 不投递，消息到此为止
          ──③ Graphiti qwen-plus 抽取──▶ 进图
```

**如果 ② 判「否」，消息压根不进图 → 影子期「看图谱」永远看不到它。**
后果具体：假如 `MemorySignalJudge` 有系统性偏差（把「我对海鲜过敏」判成寒暄），**影子期会得出「图谱质量很好」的结论，然后带着这个 bug 上线**。

→ **结论：影子期的观测对象必须包含「被 ② 判否的原文」。** 这也是 `user_semantic_memory` 表**不 DROP、改造成审计表**的原因（零建表成本）。

**盲区二：静默失败不在图里，而在 DLQ 里**

`POST /messages` 的 202 ≠ 成功；对账超时 → 重投 → 三次 → **DLQ**。**DLQ 里的消息就是「永久没进图」的** —— 它是写入链路健康度的**唯一体检指标**。

⚠️ 一旦开启影子写入，**DLQ 会积累**（初始几天几乎必然有垃圾进 DLQ）。所以 **DLQ 监控在第一期就要，不能等**。

### 5.3 四层抽样协议（P7a）

| 层 | 范围 | 理由 | 判定维度 |
|---|---|---|---|
| **层 1** | **全量**所有「改口」事件（出现 `invalid_at` 的边） | 本 Phase 核心能力，且数量天然少 | 忠实性 + 裁决正确性 |
| **层 2** | **全量**所有高危词表触发的 episode | 安全相关，容错率零 | 忠实性 |
| **层 3** | **随机 30 条**普通 episode | 量级足够给出约 ±18% 粗略误差界 | 三问全判 |
| **层 4** | ★ **随机 30 条被 `MemorySignalJudge` 判否的原文** | **唯一能发现「漏拦」的途径** | 该不该被拦 |

**每条的三问判定（人工）+ 一条动态验证**：

1. **忠实性**：这条 fact 是否忠实于用户原话？（有无无中生有 / 曲解）
2. **相关性**：这条 fact 是否值得长期记住？（还是噪音）
3. **可检索性（最有价值的一条）**：**拿一个「用户会怎么问」的自然问句去搜，能搜到它吗？**
   → 前两条是**静态看数据**，第三条是**动态验证召回链路**。哪怕 fact 抽得完美，搜不出来（embedding 质量 / query 匹配）等于白存。

### 5.4 四个量化验收指标

| 指标 | 定义 | 为什么重要 |
|---|---|---|
| **写入成功率** | 成功落库 message / 应写 message | 低于 99% 说明**链路**有问题（而**不是**抽取质量问题）——先分清是哪一类 |
| **抽取非空率** | 落库 episode 中抽出 ≥1 条 fact 的比例 | ★ **双向信号**：过高 = 判太宽（噪音入图）；过低 = 判太严（漏拦）。**这一个数字就能定位 `MemorySignalJudge` 的门限该往哪调** |
| **重复边率** | 语义相同 (source, relation, target) 的重复边占比 | 验证 `uuid` 幂等 + `dispatched_at` 防重是否真的生效 |
| **孤儿实体率** | 只有一条边、且该边已作废的 Entity 占比 | 图谱垃圾的代理指标；上线越久越该稳定在低水位 |

### 5.5 版本化上线门限 + 样本量口径（P7c）

**定稿门限（写进配置，达标才许打开 `graphiti.recall.enabled`）**：

| 指标 | 门限 |
|---|---|
| 写入成功率 | **≥ 99%** |
| 抽取非空率 | **∈ [20%, 70%]** |
| 重复边率 | **< 2%** |
| DLQ 深度 | **= 0** |
| 人工判定 | 层 1 / 层 2 **无 P0 错误**（P0 = 关于用户的关键事实错误，如过敏、健康、身份） |

→ **门限数字本身可以是拍脑袋的，但「必须有数字」这件事不能妥协。** 避免影子期结束时靠印象拍板。

**★ 样本量口径（关键）**：**影子期长度以「样本量」定，不以「时间」定。**

> **积累 ≥ 100 条 episode 且 ≥ 10 次改口事件**，才算够。

（若定「跑一周」，而一周只有 5 轮对话，层 3/层 4 的样本根本不够。）

### 5.6 验收载体（P7b）

**只读对照清单 + 导出脚本**（一期），**不做调试页**。

**形态要求**：不是「fact 列表」，而是 **「用户原话 ↔ 抽取的 fact ↔ 时间戳 ↔ 来源 episode」并排**。理由：判一条 fact 好不好，**必须同时看到用户原话**才能判断忠实性。

三张附表：
1. **主表**：四层抽样的并排对照（Markdown / CSV）
2. **DLQ 表**：进 DLQ 的消息 + 原因分类（毒消息 / 重试耗尽）
3. **判否样本表**：`MemorySignalJudge` 判否的原文 + 判定理由

⚠️ 澄清 v2 一句：v2 写「一期不做调试页」**是对的**，但容易被误读成「一期不做任何工具」。**导出脚本 ≠ 调试页** —— 脚本成本约 1 小时，一期**必须要**。

---

## 六、实施顺序与废弃清单

### 6.1 批次 B0 → B4（依赖关系已重排）

| 批次 | 内容 | 依赖 | 风险 |
|---|---|---|---|
| **B0** | **契约层**：`GraphitiProperties` + DTO + `GraphitiMemoryClient` + 独立 `RestClient`/`ObjectMapper` + **§2.2 四个字段陷阱的防御断言** + **P5b 的非空断言** + 契约自测 + 8 条实现陷阱（附录 B）的参数化测试 | 无 | **零业务风险** |
| **B1** | **写入链**：MQ 两级拓扑 + 三隐患修复 + `GraphitiScanConsumer`/`GraphitiWriteConsumer` + `MemorySignalJudge` + **高危词表快通道** + 重试/DLQ + 水位 + 幂等 + **双字段状态机** + **`GraphitiConfirmJob` 异步对账** | B0 | 中（新增链路，但开关可关） |
| **B2** | **召回链**：`GraphitiMemoryRecaller` + 双通道并行 + 预算截断 + Prompt 铁律 + `{{episodicMemory}}` 槽位 + L1 窗口扩容与时间窗兜底 + **`conversation_archive` 集合 + 回忆类意图路由**（P1/P1-新） | B1 | 中（阻塞首字，需 shadow 验证） |
| **B3** | **管理面**：`SemanticMemoryController` 改读 Graphiti + 单条/按 episode 删除 + **记忆溯源（`fact.episodes[]` 反查原话）** + 前端「我的记忆」分层展示（记忆分类**拍扁**） | B2 | 低 |
| **B4** | **灰度验收**：三开关 + 影子模式 + **导出脚本与三张附表** + Micrometer 可观测性 + DLQ 监控 + **门限达标判定** + 回滚演练 | B3 | 低 |

**建议先只做 B0**：零业务风险、把所有契约陷阱一次性钉死；B0 完成后即可用真实 DTO 做端到端契约验证（对着 `192.168.88.130:8088` 打），再决定后面怎么走。

### 6.2 废弃清单（「不搞历史包袱」的物理动作）

**删除**：`HabitExtractor`、`HabitJudge`、`SemanticDeduplicator`、`SemanticMemoryService(Impl)`、`UserSemanticCandidate(Mapper)`、`ExtractedHabit(List)`、`HabitVerdict`、`SemanticRuleType`、`SemanticHabitVO`、`AssistantConfig` 里的 `habitExtractor()` / `habitJudge()` 两个 Bean、`application.yaml` 的 `semantic.*` 段、Qdrant `user_semantic_habits` 集合定义。

**★ 保留并改造**：`UserSemanticMemory` 实体 + `UserSemanticMemoryMapper` —— 表**不 DROP**，改造成 `MemorySignalJudge` 判否的审计表（P7 层 4 的唯一数据来源）。
⚠️ **注意语义切割**：改造后这张表**不再是任何「偏好真相」的载体**（不违反铁律 2），它只存「被判否的原文 + 判定理由」，性质是**审计日志**而非记忆源。二选一见 §7.3 末尾。

**改造**：`SemanticScanConsumer` → `GraphitiScanConsumer`（拆出 `GraphitiWriteConsumer`）；`SemanticScanProducer`（保留冷却锁，消息体加水位字段）；`ChatStreamService.buildUserMemoryBlock` → Graphiti 召回 + **高危词表提交通道**；`MainChatAssistant` 加 `{{episodicMemory}}` 槽位；`CoreChatPrompt.txt` 加铁律 + 新块 + `{{context}}` 声明；`SemanticMemoryController` → Graphiti 数据源；`SemanticMemoryServiceImpl` 的类注释（「偏好 Vue3 vs 偏好 React = 0.8473，不存在可用阈值」）**保留作决策存档**。

**保留并复用**：`PromptSanitizer.sanitizeFact()`（消毒）、`ChatSummaryService`（L1 摘要）、`IntentRoutingService`（routing 骨架 + `EPISODIC_MEMORY` 分支扩语义）、`Bm25SparseEncoder`（稀疏检索）、`QdrantTemplate`（**稠密+稀疏 RRF 通用模板，`conversation_archive` 直接复用，零新代码**）、`QdrantConfig.initCollections()`（**配置驱动建集合，加 yaml 块即自动建好**）。

**数据清理**：
- Qdrant `user_semantic_habits` 集合 → **删除**
- MySQL `user_semantic_memory` 表 → **不 DROP，改造为 `MemorySignalJudge` 判否的审计表**（P7 层 4 的数据来源；结构接近，零建表成本）
- MySQL `user_semantic_candidate` 表 → **先停止写入，暂不 drop**（观察期后再定）
- MySQL `chat_message` → **加两列 + 一索引**（§3.8）

---

## 七、改动清单（逐文件）

### 7.1 新增文件

| # | 文件 | 职责 | 批次 |
|---|---|---|---|
| 1 | `config/GraphitiProperties.java` | 配置绑定：`enabled` / `write.enabled` / `write.judge-enabled` / `recall.enabled` / `recall.unsettled-window-minutes` / 超时 / 词表 | B0 |
| 2 | `client/GraphitiMemoryClient.java` | Client 接口（§2.3） | B0 |
| 3 | `client/impl/GraphitiMemoryClientImpl.java` | `RestClient` + 独立 `ObjectMapper` + 三条断言（groupIds 非空 / 陷阱防御 / 埋点） | B0 |
| 4 | `client/dto/GraphitiWriteRequest.java` `GraphitiMessage.java` `GraphitiSearchRequest.java` `GraphitiFact.java` `GraphitiWriteAck.java` `EpisodeRef.java` | DTO（§2.2） | B0 |
| 5 | `client/dto/RecalledFact.java` `RecalledFactWithLifecycle.java` | 两个**不通用**的召回结果类型（§2.3） | B0 |
| 6 | `client/GroupIdResolver.java` | 唯一的 `group_id` 生成点：`user_{userId}`（P5a/P5b） | B0 |
| 7 | `mq/GraphitiScanMessage.java` `GraphitiWriteMessage.java` | 两个消息类（**须登记进 `addTrustedPackages`**） | B1 |
| 8 | `mq/GraphitiScanConsumer.java` `GraphitiWriteConsumer.java` | 两级消费者（§3.3） | B1 |
| 9 | `mq/GraphitiMqConfig.java` | 新拓扑声明（delay / dlx / write / 3 重试 / dlq） | B1 |
| 10 | `ai/aiservice/MemorySignalJudge.java` | 实质判定（qwen-turbo + few-shot + 关断开关） | B1 |
| 11 | `service/HighRiskLexicon.java` | 高危词表（纯规则，可配置） | B1 |
| 12 | `job/GraphitiConfirmJob.java` | 异步对账（§3.6 S2） | B1 |
| 13 | `ai/rag/ConversationArchiveService.java` | `conversation_archive` 写入（对话落库时切片）+ 回忆类检索 | B2 |
| 14 | `service/GraphitiMemoryRecaller.java` | 双通道并行召回 + 合并 + 裁剪 + 分桶 | B2 |
| 15 | `graphiti/GraphitiAuditMapper.java` + `entity/GraphitiWriteAudit.java` | 判否审计表（复用 `user_semantic_memory` 表） | B1 |
| 16 | `tools/GraphitiVerifyExport.java`（或独立脚本） | 导出三张附表（§5.6） | B4 |

### 7.2 修改文件

| # | 文件 | 改动 |
|---|---|---|
| 1 | `application.yaml` | 新增 `graphiti.*` 段；`chat.memory.max-messages: 6 → 20`；`spring.data.qdrant.collections` **加 `conversation_archive`**（配置驱动自动建集合）；删除 `semantic.*` 段 |
| 2 | `mq/RabbitSemanticConfig.java` | **`addTrustedPackages` 加新消息类所属包**（精确包名 equals！）；原 `semantic.scan.queue` 补 DLX 声明 |
| 3 | `mq/SemanticScanConsumer.java` → `GraphitiScanConsumer.java` | 改造成发现层；修 catch-all；`armed` 锁释放改为**有条件**；加水位 |
| 4 | `mq/SemanticScanProducer.java` | 消息体加水位字段 |
| 5 | `ChatStreamService.java` | `buildUserMemoryBlock` → Graphiti 召回；`persistMessages()` 后加**高危词表扫描 + 快通道投递**；`buildConversationContext` 加「未沉淀原文」时间窗 |
| 6 | `ai/config/AssistantConfig.java` | 删 `habitExtractor()` / `habitJudge()`；加 `MemorySignalJudge` Bean |
| 7 | `ai/aiservice/MainChatAssistant.java` | 加 `{{episodicMemory}}` 槽位 |
| 8 | `resources/prompt/CoreChatPrompt.txt` | 加【记忆权重铁律】+ `{{context}}` 块首声明 + `{{episodicMemory}}` 槽位 + `{{userGlobalMemory}}` 块首术语 |
| 9 | `controller/SemanticMemoryController.java` | 数据源改 Graphiti；加「变更历史」只读接口（走旁路方法）；加删除接口 |
| 10 | `config/RestTemplateConfig.java` | **不动**（但 Graphiti 严禁复用该 Bean） |
| 11 | `json/JacksonObjectMapper.java` | **不动**（Graphiti client 自建 ObjectMapper 隔离） |
| 12 | `ai/config/QdrantConfig.java` | 若集合定义来自 yaml 则**不动**；若硬编码则改为配置驱动 |
| 13 | `pojo/entity/UserSemanticMemory.java` + `mapper/UserSemanticMemoryMapper.java` | **不删**，改造为判否审计表（§6.2）：字段映射改为「userId / 判否原文 / 判定理由 / 判定时刻」，移除「晋升状态」类字段 |

### 7.3 删除文件

`ai/aiservice/HabitExtractor.java`、`HabitJudge.java`、`SemanticDeduplicator.java`（如存在）、`service/SemanticMemoryService.java` + `impl/SemanticMemoryServiceImpl.java`、`mapper/UserSemanticCandidateMapper.java`、`pojo/entity/UserSemanticCandidate.java`、`pojo/*/ExtractedHabit.java`（含 List 版）、`HabitVerdict.java`、`enums/SemanticRuleType.java`、`pojo/vo/SemanticHabitVO.java`。

⚠️ **`UserSemanticMemory` 实体 + `UserSemanticMemoryMapper` 不删**，改造为审计表（§6.2）。**这是一个待你确认的二选一**：

| 选项 | 做法 | 优点 | 缺点 |
|---|---|---|---|
| **甲（本文档按此写）** | 复用 `user_semantic_memory` 表，改造字段语义 | 零建表成本；表结构接近 | 旧表名保留但语义已变，后来读代码的人可能误会 |
| **乙** | 新建 `graphiti_write_audit`，DROP 旧表 | 语义干净、职责清晰 | 多一次建表；旧数据需先导出备查 |

定稿按**甲**写（因为你在 P7a 拍的就是「复用、零建表成本」）。若你要乙，回一句即可。

### 7.4 数据面变更

| 面 | 变更 |
|---|---|
| **MySQL DDL** | `chat_message` 加 `graphiti_dispatched_at` / `graphiti_synced_at` + 索引（§3.8） |
| **RabbitMQ** | 新增：`graphiti.scan.delay.queue` / `graphiti.scan.dlx` / `graphiti.scan.queue` / `graphiti.write.queue` / `graphiti.write.retry.{30s,2min,10min}.queue` / `graphiti.write.dlq`；删除：`semantic.scan.*` 系列 |
| **Qdrant** | 新增集合 `conversation_archive`（配置驱动）；删除集合 `user_semantic_habits` |
| **Neo4j / Graphiti** | **无变更**（服务端已就绪） |

---

## 附录 A · 契约速查表（据容器源码实读，2026-10-12）

| 接口 | 方法 | 约束 | 响应 |
|---|---|---|---|
| `/messages` | POST | `{group_id, messages:[{content, role_type(user/assistant/system), role(**必填字段，可 null**), uuid?, name?, timestamp?, source_description?}]}`；**每条 message 独立成 episode**；**202 异步** | `{message, success}` |
| `/search` | POST | `{group_ids[]（**可选可 null，漏传=跨租户全局**）, query(必填), max_facts=10}` | `{facts:[FactResult]}`；**不过滤 invalid_at、无 group_id** |
| `/episodes/{group_id}` | GET | `last_n` 必填；**纯时间倒序、无 offset/游标**；未知参数静默忽略 | `[EpisodicNode]`（**非 DTO**）；时间格式 `...Z` |
| `/entity-edge/{uuid}` | GET / DELETE | 单条 fact 详情 / 删除 | `FactResult` / `{message,success}` |
| `/episode/{uuid}` | DELETE | 按 episode 删除 | `{message,success}` |
| `/group/{group_id}` | DELETE | **按组清理的正确姿势** | `{message,success}` |
| `/clear` | POST | ⚠️ **无参数 = 整图清空**，生产禁用 | `{message,success}` |
| `/healthcheck` | GET | — | `{"status":"healthy"}` |
| `/get-memory` | POST | 需单数 `group_id` + `center_node_uuid` + `messages[]` | 本 Phase 不使用 |

**`FactResult` 原始字段**：`uuid, name, fact, valid_at(null), invalid_at(null), created_at(必填), expired_at(null), source_node_uuid, target_node_uuid, episodes[]`
**`/search` 时间格式**：`...345970+00:00`（微秒 + offset）→ **必须 `OffsetDateTime`**。
**`/episodes` 时间格式**：`...Z`（**与 `/search` 不同**，`OffsetDateTime` 两者都能吃）。

## 附录 B · 实现陷阱清单（★ 全部为实测/实读所得）

| # | 陷阱 | 后果 | 对策 |
|---|---|---|---|
| 1 | `messages[]` 漏包一层 | 422 | 唯一入口构造器强制包装 |
| 2 | `role` 不传 | 422（**无 default**） | DTO 不加 `NON_NULL`，显式传 `"user"` |
| 3 | `role_type` 越界 | 422（`Literal`） | Java 枚举 |
| 4 | `timestamp` 不传 | 历史对话被盖「处理时刻」→ **裁决失效** | 显式传 `chat_message.created_at` |
| 5 | `group_ids` 漏传/null/写成单数 | **跨租户全局检索且 HTTP 200** | Client 内**强制断言非空**，空则抛 `IllegalStateException` |
| 6 | 复用全局 `JacksonObjectMapper` | Graphiti 时间戳解析**必然抛异常** | client 自建 ObjectMapper |
| 7 | 复用 `restTemplate`（60s read timeout） | 钉死 MQ 消费线程 | 自建 `RestClient`，超时分档 |
| 8 | `/search` 不过滤 `invalid_at` | 新旧偏好并存 → 模型精神分裂 | `recallActive()` 内部过滤 |
| 9 | `/search` 返回**无 `group_id`** | 无法自证 fact 归属 | 隔离责任 100% 落在 `group_ids` |
| 10 | ★ `edges.py:271` 把 `expired_at` 描述写成 **node** invalidated | 误以为 expired 才是作废标记 | **只判 `invalid_at`**（§2.6） |
| 11 | ★ 新边**可能当场被作废**（`edge_operations.py:838-840`） | 「写入成功 ≠ 事实生效」 | 成功判据**不能**看 `invalid_at` 为空，只能按 **uuid 存在性** |
| 12 | `POST /clear` 无参数 | **整图清空**（所有租户） | 生产禁用；按组清理用 `DELETE /group/{id}` |
| 13 | 新消息类未登记 `addTrustedPackages` | **静默丢弃**（deliver+1、ack 恒 0、方法体不执行） | 包名**精确 equals**；类放 `com.yangmf.mini_nodepad.mq` |
| 14 | 重试用单队列多档 TTL | **队头阻塞** | 3 个静态 TTL 队列 |
| 15 | 用 `@Retryable` 做 MQ 重试 | 同线程循环、消费线程被占住 | 交给 TTL+DLX |
| 16 | 消费者内轮询确认（v2 的 S1） | **占住消费线程最多 15 分钟** | 投递即 ack + 异步对账（§3.6） |
| 17 | `source_description` 塞技术标记 | 语义污染（前端会展示给用户） | 写**人话式来源说明** |
| 18 | `PostConstruct` 期就绪竞速 | 首次调用 `ServiceUnavailable` | 客户端侧 fast-fail + 重试（服务端已有 wait-for-neo4j） |

## 附录 C · 证据来源

| 断言 | 证据 |
|---|---|
| 每条 message 独立成 episode、`uuid` 可指定、`reference_time = m.timestamp` | 容器内实读 `/app/graph_service/routers/ingest.py` |
| `role` 无 default（必填）、`timestamp` 默认 `utc_now()`、`AddMessagesRequest` 仅 `group_id`+`messages` | 容器内实读 `dto/common.py`、`dto/ingest.py` |
| `FactResult` 字段与序列化格式、`SearchQuery.group_ids` 可空、`max_facts=10` | 容器内实读 `dto/retrieve.py` |
| **「有效」只判 `invalid_at`** | `graphiti_core/search/search_helpers.py:57` 官方口径 + `edge_operations.py:569/822/838` 三条赋值路径 + Neo4j 直查 10 条边（`ia_only=0 / ea_only=0`） |
| **新边可能当场被作废** | `graphiti_core/utils/maintenance/edge_operations.py:838-840` |
| **`expired_at` 字段描述有误** | `graphiti_core/edges.py:271` |
| 中文 BM25 能命中但分数不可分 → episode 层无向量 | 四轮侦察：`EpisodeSearchMethod` 只有 `bm25`、`EpisodicNode` 无 embedding、Neo4j 35 索引无 VECTOR、Lucene 实测 6/6 命中但 React 真命中 0.045 vs 噪音 0.035 |
| `/episodes` 纯时间倒序、未知参数静默忽略、无游标 | 实测（加 `&query=React` 返回逐字节一致） |
| L1 = `MessageWindowChatMemory(maxMessages=6)` + `RedisChatMemoryStore(无显式 TTL)` | `ChatMemoryProviderConfig.java` + `application.yaml` |
| Qdrant 两集合及用途 | `application.yaml: spring.data.qdrant.collections` |
| MQ 三隐患（无 DLX / catch-all / armed 无条件释放） | `RabbitSemanticConfig.java`、`SemanticScanConsumer.java`、`SemanticScanProducer.java` |
| 注入点与槽位名 | `CoreChatPrompt.txt`、`MainChatAssistant.java`、`ChatStreamService.java` |
| 废弃面（Phase 3 全部类与 Bean） | 全量 grep `SemanticMemoryService|HabitExtractor|HabitJudge|SemanticDeduplicator` |

## 附录 D · B1 阶段必须完成的实机验证项

| # | 验证项 | 方法 | 判据 |
|---|---|---|---|
| 1 | `add_episode` 对**重复 uuid** 的行为 | 同 uuid 投两次，观察 `/episodes` 与 `/search` | 抛错 / 覆盖 / 重复建边，三选一，据此定幂等策略 |
| 2 | `/episodes` 返回体里 **uuid 字段的确切名字** | GET 一次看原始 JSON | 有稳定 uuid 才能按 uuid 比对；无则退化为「数量 + valid_at 区间」 |
| 3 | `source_description` **是否参与 LLM 抽取上下文** | 同内容、不同 `source_description` 两条 episode 对照 | 若参与 → 严禁放可被误抽的文本 |
| 4 | **乱序投递不改裁决** | 同一对话正序/倒序各投一次 | 最终图谱状态一致（§3.4 结论 4 的实证） |
| 5 | `DELETE /entity-edge/{uuid}` 在 v2 镜像可用 | 真实调用一次 | 遗忘权依赖它 |
| 6 | `RedisChatMemoryStore` 的**默认 TTL** | 写一条后观察过期 | 若很短 → 必须显式设置 ≥ 30min |
| 7 | 单条 `POST /messages` 的**端到端落库耗时**分布 | 投 20 条，记录 202→可用 的间隔 | 校准 `GraphitiConfirmJob` 的超时阈值（当前取 3min 兜底） |

