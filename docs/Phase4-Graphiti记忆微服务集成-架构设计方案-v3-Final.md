# Phase 4 · Graphiti 记忆微服务集成 — 架构设计方案 v3-Final

> **状态**：**Final（rev.2）—— 可下发开发**。取代 v3-定稿（`Phase4-...-v3-定稿.md` 保留作独立评审的过程记录）与 v2。
> 九轮决策（P1~P8 + P1-新 + P3-补）已全部拍板；并入九轮中所做的**自查纠正**（4 处，见 §0.2）；
> 并入**第一轮独立评审的 7 项补丁**（5 个 P0 + 2 个防雪崩，见 §0.5）与 **2 项评审驳回记录**（见附录 E）；
> ★ **并入第二轮独立评审的 3 颗 P0/P1 暗雷修复**（见 §0.6），并新增 **§0.7 开发铁律（6 条，B0/B1 不得妥协）**。
> **所有待确认项已清零。**
> 本文档为**实施方案**，不含业务实现代码（接口定义与伪代码除外）。
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
| **P3-补** | 画像类走意图还是常驻 | **② 不拆意图，画像常驻注入**（已拍板；实现见 §4.2 / §4.2.1） | 召回管线 |
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

### 0.3 待确认项 → **已全部清零**

| 原待确认项 | **最终决议** |
|---|---|
| P3-补 的「双通道」（§4.2） | **采纳双通道**：画像通道（模板化 query）+ 情景通道（用户本轮原话），并发、均不缓存。反向论证见 §4.2。 |
| §7.3 审计表：复用 `user_semantic_memory` vs 新建 `graphiti_write_audit` | **乙 —— 新建 `graphiti_write_audit`，DROP 旧表**（rev.2 推翻 rev.1 的甲；理由见 §7.3：字段需求已超出旧表结构）。语义切割见 §6.2。 |
| §3.2 高危快通道是否需要独立队列 | **不需要**。快通道直接投 `graphiti.write.queue`，但该队列**声明为优先级队列**（补丁 6），高危消息 `priority=10` 插队。 |

### 0.4 四面一体（本 Phase 的全部工作量）

```
① 契约层   Graphiti Client + DTO + 安全断言（B0）
② 写入链   MQ 两级队列 + 实质判定 + 高危快通道 + 异步对账（B1）
③ 召回链   双通道并行召回 + Prompt 铁律 + 四层优先级（B2）
④ 管理面   遗忘权 + 记忆溯源 + 前端分层展示（B3）
⑤ 影子验收 四层抽样 + 量化门限（B4）
```

### 0.5 ★ 独立评审后的 7 项补丁（本版新增，全部已并入）

评审文档（2026-10-12）在 v3-定稿基础上又挖出 7 颗雷。**逐条复核后全部成立**，本版已并入：

| # | 补丁 | 级别 | 落点 |
|---|---|---|---|
| **补丁 1** | **重试死锁**：可重试异常若不清除「防重占位」，消息会被自己的防重机制永久拒收 | **P0** | §3.5 / §3.6 / §3.7 |
| **补丁 2** | **Top-N 截断**：`max_facts` 按目标条数传 → 过滤作废后可能为空，第 N+1 条有效事实永远捞不到 | **P0** | §2.3 / §4.2 |
| **补丁 3** | **对账 `last_n` 被挤出窗口**：硬编码 `last_n` → 目标 uuid 落在窗口外 → 误判失败 → **无限重投** | **P0** | §3.6 |
| **补丁 4** | **429 被误杀进 DLQ**：限流归入 4xx 毒消息 → 高峰期新偏好成批永久丢失 | **P0** | §3.7 |
| **补丁 5** | **排序踩踏**：双通道合并后按 `validAt` 全局倒排 → 近期情景挤掉长期画像 | **P0** | §4.2 |
| **补丁 6** | **优先级队列**：高危消息需在 write 队列内**插队**，而不只是绕过 5 分钟 | 防雪崩 | §3.2 / §3.10 |
| **补丁 7** | **背压熔断**：积压超阈值 → 暂停常规投递、只保高危 | 防雪崩 | §3.10 |

**⚠️ 本版对评审原方案做了 3 处偏离（均有理由，非未采纳）：**

| 评审原方案 | **本版做法** | 理由 |
|---|---|---|
| 补丁 1：`redis.del(dedup_key)` | **彻底删除 Redis 第二层防重**，改为 **DB 事前 CAS 占位 + 可重试失败回滚** | Redis 锁的 TTL 与「可重试」本质冲突，`del` 只是打补丁；而 v3 §3.8 自己就写了「Redis 不是权威源」。改为 DB CAS 后**同时**满足防重、可观测、可自愈、可审计（详见 §3.5） |
| 补丁 7：用 **DLQ 深度**做熔断信号 | 改用 **主队列积压深度 + 未确认数（`dispatched_at` 超 15min 未 `synced_at`）** ⚠️ **但第二半随后被暗雷 2 证伪 → 见 §0.6，最终只认 MQ 积压** | DLQ 只在**重试全部失败后**才涨，等它涨说明数据已经丢了，**反应太慢**；未确认数我们已有 SQL，是最灵敏的指标 |
| 补丁 7：「以丢弃普通记忆为代价」 | 改为**「延迟投递」而非「丢弃」** | 双字段状态机天然支持补投：常规消息 `dispatched_at` 保持 NULL，熔断解除后由下一次扫描/补投任务自动补上。**不必丢数据** |

**另附 2 项评审误判（本版不改架构，驳回记录见附录 E）**：内网多租户隔离、层 4 抽样的「全量随机」替代方案。

### 0.6 ★★ 第二轮独立评审的 3 颗暗雷（rev.2 新增，全部已并入）

| # | 暗雷 | 级别 | 落点 |
|---|---|---|---|
| **暗雷 1** | **对账任务变成「垃圾强制入图机」**：`MemorySignalJudge` 判否的消息，`dispatched_at` / `synced_at` **双 NULL** —— **恰好满足对账任务的扫描条件** → 前门拦下的闲聊被后门无差别重投，图谱被污染 | **P0（逻辑死循环）** | §3.6 / §3.6.1 |
| **暗雷 2** | **背压熔断的「报喜不报忧」盲区**：可重试失败会**回滚** `dispatched_at = NULL`，于是 Graphiti 全挂时「未确认数」**反而归零** → 熔断器认定系统健康、继续猛灌 | **P0（监控假死）** | §3.10 |
| **暗雷 3** | **`last_n` 的时间窗陷阱**：按 `chat_message.created_at` 算窗口，**历史补投 / 长重试链**的消息算不进去 → 窗口罩不住目标 uuid → **无限重投** | **P1（机制失效）** | §3.6.1 |
| **★ 暗雷 4**（**实测发现**，非评审提出） | **`add_episode` 的 `uuid` 语义反直觉**：传一个**新** uuid 会被当成「更新一个已存在的 episode」→ `NodeNotFoundError` → **job 静默失败、什么都不写**，而 HTTP 早已返回 **202**。★ **rev.1 的设计恰好要求传确定性 uuid（`cm_+id`）→ 会 100% 静默丢数据** | **P0（静默丢数据）** | §3.5 |

**⚠️ 本版对第二轮评审原方案做了 3 处实质修正（有理由，非未采纳）：**

| 评审原方案 | **本版做法** | 理由 |
|---|---|---|
| 暗雷 1：给对账 SQL 加 `LEFT JOIN 审计表` | **把「对账」与「补投」拆成两个职责单一的任务**：`GraphitiConfirmJob` 只管「**已投递**但未确认」；`GraphitiReplayJob` 管「**该投但没投出去**」。**两者都带 `NOT EXISTS(审计表)`** | 评审的修法能挡住垃圾，但两条职责仍混在一条 SQL 里。**拆开后「判否的消息」连进入对账视野的可能性都没有**（它 `dispatched_at` 恒为 NULL）—— 审计排除降为第二道防线。★ **暗雷 1 与暗雷 3 由这一次拆分同时解决** |
| 暗雷 3：`X = 未确认数`（`synced_at IS NULL AND NOT IN 审计`） | `X` 必须覆盖**「比最老未确认消息更新的所有 episode 数」**，不是「未确认数」本身 | ⚠️ 稳态下两者近似（未确认面 ≈ 3min 流量），但**病态场景差一个数量级**：一条卡住 2 小时、其间正常落了 200 条，`未确认数 = 1` → `last_n = 12` → 那条**永远找不到** → 恰好就是评审要修的无限重投。定稿改用**「投递时间窗」计数**（§3.6.1） |
| 暗雷 2：只把信号换成 MQ 积压 | ① 信号换 MQ 积压（**采纳**）；② 老实说清 **AMQP 被动声明只返回 ready 数**（不含 unacked，故 `prefetch=1` 是前提）；③ ★ **熔断期被跳过的数据要有「补投责任人」** | ① 采纳；② 量纲不能含糊；③ 评审要的「只延迟不丢弃」**需要一个新的定时任务才成立**，否则「延迟」会静默退化成「永久不投」 |

### 0.7 ★★ 开发铁律（B0 / B1 强制约束，不得妥协）

> 以下 6 条为**终态约束**。编码时逐条对照，任一条被绕过即为不合格交付。

| # | 铁律 | 自查点 | 落点 |
|---|---|---|---|
| **1** | **唯一防重防线 = DB CAS 占位 + 失败回滚**。彻底废弃 Redis setnx 防重锁；一切可重试异常**必须**回滚 `graphiti_dispatched_at = NULL` | 全工程 grep `setnx` **零命中**（`armSessionScan` 的 5min 冷却锁除外） | §3.3 / §3.5 / §3.8 |
| **2** | **对账 `last_n` 严禁硬编码**，且 SQL **必须排除审计表中的判否数据** | 用 §3.6.1 的动态公式；`NOT EXISTS(审计表)` 必须同时出现在 Confirm 与 Replay 两个任务的 SQL 里 | §3.6 / §3.6.1 |
| **3** | **429 / 403(quota) 必须优先识别**，跳过 30s 短重试，直接进 2min 或 10min 档 | `isRetryable()` **先看码、再看 body 关键词**；严禁 `status/100 == 4` 一刀切 | §3.7 / §3.7.1 |
| **4** | **背压熔断只认 MQ 积压**（`write` + 3 个 `retry` 队列 ready 之和），**不看 DLQ、不看 MySQL 软状态**；熔断期跳过的数据由 **`GraphitiReplayJob`** 补投，做到「只延迟、不丢弃」 | 熔断器代码里不得出现「未确认数」作为**触发**条件 | §3.10 |
| **5** | **召回截断铁律**：宽拉取（`limit × 4`）→ 严过滤（剔除 `invalidAt != null`）→ **后**截断。**截断必须发生在过滤之后** | `recallActive()` 内部顺序不可颠倒 | §2.3.1 / §3.6.1 |
| **6** | **双通道物理隔离**：画像与情景**独立发请求、独立过滤、独立截断**，**严禁合并后全局排序**；分别灌入 `{{userGlobalMemory}}` 与 `{{episodicMemory}}` | 见 §4.2.1 三条约束 | §4.2.1 |

**★ 铁律 1 的前置动作 —— ✅ 已完成（2026-10-12 实机实测，见 §3.5）**：

向测试库投递两次相同 UUID 的实测，**推翻了 rev.1 的核心假设**，并挖出一条全新的**静默丢数据**路径：

> **`uuid != null` 的语义 = 「update 一个已存在的 episode」**。
> 新建时传 uuid → `NodeNotFoundError` → **job 静默失败、什么都不写**，而 **HTTP 早已返回 202**。

**⇒ 定稿改为：写入一律 `uuid = null`，确认锚点改用 `name` 字段（`"cm_" + chatMessageId`）。**
**⇒ 且服务端对「重复投递」零去重能力 → CAS 从「第二层防线」升为「唯一防线」。**

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
        String uuid,              // ★★ rev.2：写入时【必须为 null】。非 null 的语义是
                                  //    "update 一个已存在的 episode"，传新 uuid 必抛
                                  //    NodeNotFoundError 并静默丢弃（见 §3.5）
        String name,              // ★ rev.2：改由它承载确认锚点 "cm_" + chatMessageId
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

    /** 唯一召回出口：内部已完成 ① groupIds 非空断言 ② 宽拉取 ③ invalidAt 过滤 ④ 消毒 ⑤ 截断
     *  @param limit 调用方期望的【最终】条数；HTTP 层自动放大为 limit × FETCH_FACTOR 再截回（补丁 2） */
    List<RecalledFact> recallActive(String userId, String query, int limit);

    /** 显式命名的旁路：排障 / 审计 / 「我的记忆」页面的「变更历史」 需要看已作废事实 */
    List<RecalledFactWithLifecycle> recallIncludingInvalidated(String userId, String query, int limit);

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

### 2.3.1 ★ 补丁 2：宽拉取（`limit × FETCH_FACTOR`）—— 防「Top-N 截断」导致有效记忆丢失

**雷区**：Graphiti 的 `/search` **不过滤已作废事实**（`invalid_at`，已实测）。若把 `max_facts` 直接当成「我要 5 条」，服务端返回的是**匹配度最高的 5 条**——而这 5 条**可能全部已被作废**（用户改口越频繁，概率越高）。Java 侧一过滤 → 召回结果为 **0**；而**真正有效的那条**（匹配度排第 6）**根本没被拉回来**。

**这不是「精度问题」，是「召回为空」的功能性故障** —— 且它只在「用户改过口」时发生，恰好是本 Phase**唯一的存在理由**（最该work的场景最容易失效）。

**定稿策略：宽拉取 → 严过滤 → 后截断**

```
recallActive(userId, query, limit = 5)
  │
  ├─ HTTP 请求 max_facts = limit × FETCH_FACTOR        (FETCH_FACTOR 默认 4)
  │     → 取回 20 条候选
  ├─ 客户端过滤 invalidAt != null                       （淘汰作废）
  ├─ 客户端消毒 sanitizeFact()
  └─ stream().limit(limit)                              ← ★ 截断发生在【过滤之后】
```

**四条必须遵守的约束**：

| # | 约束 | 原因 |
|---|---|---|
| 1 | `FETCH_FACTOR ≥ 4`（`limit=5` → 请求 20） | 作废比例未知；4× 是在「单次检索预算 2s」内可承受的放宽幅度。**若影子期观测到「过滤后不足 limit」的频次 > 5%，再上调** |
| 2 | ★ **截断必须在过滤之后**，绝不能反过来 | 反过来就是本补丁要修的雷 |
| 3 | 放大**只发生在 Client 内部**，调用方永远只看 `limit` | 让调用方传 `maxFacts` = 把「该放大多少」这个知识泄漏到每个调用点 |
| 4 | `FETCH_FACTOR` 写进 `GraphitiProperties`（`recall.fetch-factor`） | 影子期要按数据调参，硬编码就得改代码 |

⚠️ **能力边界（不要误解）**：本补丁**不能**解决「图谱里压根没有有效事实」（那是写入链的问题）。它只解决**「有效事实存在、却没被拉进候选集」**。

⚠️ **与补丁 5 的配合**：两条通道**各自独立**执行本流程，**绝不先合并再截断**（见 §4.2）。

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
                                    ① 按水位取窗口（★补丁1：水位即幂等，删掉 Redis 锁）
                                    ② 逐轮过 MemorySignalJudge
                                    ③ 通过的轮次 → 逐条投 graphiti.write.queue（N=1）
                                            │
高危词表命中（对话结束时立即投递，绕过 5 分钟）─┤  priority = 10  ★补丁6（插队）
                                            ▼
                        graphiti.write.queue   ★ 唯一 Graphiti 写入口
                                               【x-max-priority = 10】
                                            │
                                    GraphitiWriteConsumer (concurrency=1, prefetch=1)
                                    ① CAS 占位   UPDATE dispatched_at = now()
                                                   WHERE id = ? AND (dispatched_at IS NULL
                                                                   OR dispatched_at < now-3min)
                                       → 影响 0 行 = 已被投过 → 直接 ack 跳过   ★补丁1
                                    ② POST /messages（单条）
                                    ③ 收 202 → 立即 ack（不等落库；确认交对账任务）
                                            │
        ┌────────────────────────┬──────────┴───────────────────┐
        ▼                        ▼                              ▼
     成功 ack      可重试失败(连接拒绝/超时/5xx/429)         毒消息(400/422)
                   ★ 先回滚 dispatched_at = NULL（补丁1）         │
                   → 按 attempt 投对应重试队列                     ▼
                      ├→ retry.30s.queue   (TTL 30s, DLX→write)    graphiti.write.dlq
                      ├→ retry.2min.queue  (TTL 2min, DLX→write)
                      └→ retry.10min.queue (TTL 10min,DLX→write)
                         ★ 429/403 直接进 2min/10min 档（补丁4：跳过 30s 撞墙）

【独立，与上述链路解耦】对账 / 补投 —— ★ 两个【职责单一】的任务（暗雷 1 + 暗雷 3 修复）

  A. GraphitiConfirmJob（每 5 分钟）—— 只管「已投递、但没确认落库」
     ① 目标集 = WHERE synced_at IS NULL
                     AND dispatched_at IS NOT NULL            ← ★ 关键：只看"已投递"
                     AND dispatched_at < now() - 3min
                     AND NOT EXISTS(审计表)                    ← ★ 判否消息天然不在内（第二道防线）
     ② 动态 last_n（按【投递时间窗】算，见 §3.6.1，非 chat_message.created_at）
     ③ GET /episodes/{groupId}?last_n=<动态值> → 按 uuid 比对
     ④ 命中 → UPDATE synced_at
        未命中 → confirm_rounds += 1
                 rounds < MAX(8) → 重投（dispatched_at 置 NULL → 投 write.queue）
                 rounds ≥ MAX     → ★ 移入审计表(reason=CONFIRM_TIMEOUT) 并停投  ← 防无限重投

  B. GraphitiReplayJob（每 10 分钟）—— 只管「该投但没投出去」
     目标集 = WHERE synced_at IS NULL
                     AND dispatched_at IS NULL                ← ★ 暗雷 1 的正面修复
                     AND NOT EXISTS(审计表)
                     AND created_at < now() - 5min            ← 避开正在被 scan 处理的正常消息
     动作   = 逐条投 graphiti.write.queue（priority = 1）
     ← 它是「熔断期被跳过」「scan 崩溃漏投」两类数据的【唯一补投责任人】（铁律 4）
     ← 常规路径扫不到的垃圾不会被它捞走：判否的已进审计表，NOT EXISTS 直接排除

【旁挂】GraphitiBackpressureGuard（每 30s 评估一次）                          ★补丁7 + 暗雷2
   信号 = write.queue.ready + retry.30s.ready + retry.2min.ready + retry.10min.ready
          （★ 只认 MQ 物理积压；不认 MySQL 软状态、不认 DLQ —— 见 §3.10）
          量纲提醒：AMQP 被动声明只返回 ready，不含 unacked；故 concurrency=1/prefetch=1 是前提
   超阈值 → 冻结常规投递（高危快通道仍放行）；恢复 → 自动解冻，积压由 ReplayJob 补投【延迟≠丢弃】
```

**四个关键设计点**：

1. **`graphiti.write.queue` 是唯一 Graphiti 写入口**。常规路径（scan 消费者逐条投）与高危快通道（直接投）**汇入同一个队列、同一个消费者** → 投递状态写入逻辑只有一处，重试/DLQ 只有一套。
2. **`N=1` 是投递粒度**（P8a）：scan 消费者发现 N 条待投时**循环投 N 次**，而不是一次 POST 带 N 条。理由见 §3.9。
3. **重试队列必须是 3 个「静态 TTL」队列**：RabbitMQ 队列 TTL 是**队列级**属性，队首消息的 TTL 会挡住后面的短 TTL 消息（**队头阻塞**）。单队列 + 多档 TTL 是错的。
4. **`concurrency=1 / prefetch=1` 不是保守，是必须**：Graphiti 侧 `AsyncWorker` 是**单协程串行**消费 `asyncio.Queue`，压再多并发只是把排队搬到服务端，而我们这边反而多占线程。
5. ⚠️ **新消息类必须登记进 `RabbitSemanticConfig.jacksonMessageConverter()` 的 `addTrustedPackages`** —— 白名单是**包名精确 `equals`**（源码注释已有血泪记录：只写父包不生效，表现为「队列正常清空、deliver+1 但 ack 恒为 0、消费者方法体从不执行」）。若新类放在 `com.yangmf.mini_nodepad.mq` 则天然已覆盖。
6. ★（**补丁 6**）**`graphiti.write.queue` 声明为优先级队列**：`x-max-priority = 10`。常规消息 `priority = 1`，高危快通道消息 `priority = 10`。
7. ★（**暗雷 1**）**「判否」是一个终态，不是一个「尚未投递」**。`MemorySignalJudge` 判否的轮次写入**审计表**（`reason = JUDGE_REJECTED`），该表是**「永不被（重）投递」的权威标记**。→ **Confirm / Replay 两个任务的 SQL 一律带 `NOT EXISTS(审计表)`**；`dispatched_at IS NULL` 因此**不再等于「待投递」**，必须与审计表一起判断。
8. ★（**优先级保全**，配合补丁 6）**`priority` 是消息属性，不是队列属性**。凡是我们**自己构造 `Message`** 重新发布的地方（消费者把失败消息投进 `retry.*` 队列、`GraphitiReplayJob` 补投），**必须显式 `setPriority(原优先级)`**，否则高危消息失败一次就降级成平民。⚠️ 而 `retry.*` → `write.queue` 那一跳由 **broker 的 TTL+DLX 代劳**，属性是否被保留**必须实测**（附录 D #11），不能假设。
9. ★（**暗雷 3**）**对账任务的扫描与窗口计算，只允许看「已投递」的消息**（`dispatched_at IS NOT NULL`）。**任何把 `dispatched_at IS NULL` 纳入对账视野的写法都是错的** —— 它同时会把判否消息和熔断期跳过消息误判成「投递失败」。

**高危快通道的拓扑定稿（P4 + 补丁 6）**：P4 拍的是「独立队列 + 独立 DLX」。经拓扑设计 + 评审补丁，**最终形态 = 快通道直接投 `graphiti.write.queue`（不新建队列）+ 该队列声明为优先级队列 + 高危消息 `priority = 10`**。

- 省掉一整套拓扑，天然复用 CAS 占位与重试/DLQ；
- 同时拿到「高危插队」能力。**用优先级比用独立队列更准** —— 独立队列只能保证「不被常规消息**堵在队列外**」，而优先级能保证「排在所有常规消息**之前**」。

⚠️ **三个必须知道的优先级队列事实**（否则这个补丁会白做或出事）：

| # | 事实 | 后果 |
|---|---|---|
| 1 | **优先级只在队列积压时生效**。消费者空闲时消息一到就被推走，优先级无意义 | 它是为「积压场景」设计的 —— 与**补丁 7（熔断）是一对**，单独做没有意义 |
| 2 | **单消费者串行处理，插队不免除等待**。队列积压 10 条时，高危消息从「等 10×45s」降到「下一个就是它（≤45s）」，**不是 0 等待** | 不要期待「即时」；它保的是「高危**先于**普通被处理」，不是「高危立刻被处理」 |
| 3 | ⚠️ **队列已存在且参数不同时，`queue.declare` 会 406 PRECONDITION_FAILED** | 加优先级参数后**必须先删队列重建**（运维步骤见 §7.4）。生产环境务必先确认队列为空 |

### 3.3 消费者流水线

**GraphitiScanConsumer（发现层）**

```
① 取窗口    按 sessionId 取 [水位, now] 之间的 user 轮次（MySQL chat_message；
            @TableLogic 自动排除 regenerate 软删；★ 只取 user —— 沿用既有裁决：
            "AI 回答中的客串假设句会诱导抽取器产生自我强化幻觉"）
② 实质判定   ★风险三  逐轮过 MemorySignalJudge（二值），只保留"含用户属性/事实"的轮次
            ⚠️ 判否的轮次必须落【审计表】—— 这是 P7 层 4 抽样唯一的数据来源（见 §5.3）
③ 逐条投递  每个通过的轮次 → 一条 GraphitiWriteMessage → graphiti.write.queue (priority=1)
            ★ rev.2：uuid 传 null；确认锚点改用 name = "cm_" + chatMessageId
            timestamp = chat_message.created_at（★ 必须显式传，否则被盖成"处理时刻"）
            ★ 投递前复核 NOT EXISTS(审计表) —— 判否 / 已隔离的消息永不被（重）投（暗雷1 兜底）
④ 水位推进  Redis SET graphiti:scan:watermark:{sessionId} = 本轮最大 created_at
⑤ 背压检查  ★补丁7 若 GraphitiBackpressureGuard 处于熔断态 → 直接 ack 返回（不投递）
            ★ 熔断态下【仍然推进水位】（避免下一轮重复判定）——
              被跳过的消息 dispatched_at 保持 NULL，由 GraphitiReplayJob 承接（铁律 4）
```

★ **补丁 1：原「① 幂等门 Redis setnx(scan:dedup:{sessionId}:{watermark}, TTL 1h)」已删除。**

理由（这是一个**双料 bug**）：

1. **死锁**：若 scan 中途抛异常，锁已占、水位未推进 → 下一轮用**同一个 key** 再 setnx → **被自己的锁挡住** → 该轮**永久漏扫**。
2. **它本来就是冗余的**：**水位本身就是幂等** —— 水位推进后不再回头，同一条消息天然不会被扫第二次。setnx 提供的「额外幂等」是**零收益 + 一个死锁风险**。

**第 ④ 步（水位）是对隐患 3 的结构性根治**：有了水位，即使某一轮漏扫，下一轮也能从水位继续 —— 而不是只靠「用户恰好又说话了」。

**GraphitiWriteConsumer（投递层，N=1）**

```
① CAS 占位  ★补丁1  UPDATE chat_message SET graphiti_dispatched_at = now()
                      WHERE id = ? AND (graphiti_dispatched_at IS NULL
                                        OR graphiti_dispatched_at < now() - 3min)
            → 影响 0 行 = 已被投递过 → 直接 ack 并 return（不去 POST）
            → 影响 1 行 = 占位成功，继续
② 投递      POST /messages { group_id: GroupIdResolver.of(userId),
                             messages: [ 单条 ] }        ← ★ N=1
③ 收 202    → 立即 ack（不等待落库确认；确认由 GraphitiConfirmJob 异步做）
④ 异常分类  ★补丁1+4 见下表
⑤ 重投保全  ★ 投 retry.* 队列时必须【显式】setPriority(原值) 且 attempt+1
              —— priority 是消息属性，自己构造 Message 就必须自己带（暗雷2/补丁6 配套）
⑥ 前置复核  ★ 毒消息判定前先查审计表：若该 message 已在审计表（已被永久隔离）
              → 直接 ack 丢弃，不 POST、不 DLQ（暗雷1 的第三道防线）
```

**第 ④ 步的完整分类表（补丁 1 + 补丁 4）**

| 异常 | 分类 | 动作 |
|---|---|---|
| **400 / 422** | **毒消息**（载荷错误，重试无意义） | **保留占位**（不回滚）+ 投 `graphiti.write.dlq` |
| **429 / 403（配额耗尽）** | ★（补丁 4）**限流，绝不是毒消息** | **回滚占位** + **直接进 2min 或 10min 档**（跳过 30s，避开持续撞墙） |
| **连接拒绝 / 超时 / 5xx / 502** | 可重试 | **回滚占位** + 按 `attempt` 投对应重试队列（30s / 2min / 10min） |
| **任何未预期异常** | 保守可重试 | **回滚占位** + 按 `attempt` 投重试队列（`attempt >= 3` → DLQ） |

★ **「回滚占位」是本补丁的核心动作**：`UPDATE chat_message SET graphiti_dispatched_at = NULL WHERE id = ?`。

不做这一步，消息会被**自己的防重机制永久拒收**：重试队列 30s 后把它投回来 → CAS 占位发现 `dispatched_at` 非空 → 直接 ack 跳过 → **整条 MQ 重试链路当场瘫痪**（评审原话：「撑不过第一波晚高峰」）。

⚠️ **为什么用「DB CAS + 回滚」而不是「Redis setnx + del」**：

| 维度 | Redis setnx + del（评审原方案） | **DB CAS + 回滚（定稿）** |
|---|---|---|
| 防重 | ✅ | ✅ |
| 死锁 | 需手动 `del`，遗漏一处就死锁 | **天然**：`dispatched_at < now-3min` 自动放开 |
| 可观测 | ❌ 只能看 Redis key | ✅ 一条 SQL 查「哪些投了没落库」 |
| 可补投 | ❌ | ✅ 扫 `dispatched_at IS NULL` |
| 权威性 | 易失（重启/淘汰丢锁） | MySQL 权威 |
| 组件数 | 多一个 | **零新增** |

→ **v3 §3.8 自己就写了「Redis 不是权威源」**。既然权威状态已在 MySQL，Redis 这层是**易失的第二真相源**；删掉它**同时**消除死锁与文档矛盾。

⚠️ **3 分钟放宽窗口为何安全**：写入 read timeout 是 **2s**（§2.5），远小于 3min → 「占位后 3 分钟内允许接管」不会造成真正的并发双投。

⚠️ **一个必须遵守的顺序约束**：**POST 之前**先 CAS 占位（**不是之后**）。若在 POST 之后才写 `dispatched_at`，则 ack 丢失导致的 MQ 重投会在「写字段之前」再次 POST → 重复 episode。**CAS 前置把这个窗口关掉。**

**`source_description` 的正确用法（P4 定稿）**：

- **常规投递**：写**会话来源**，如 `"session:S123 5min-batch"`
- **高危快通道**：写**人话式来源说明**，如 `"用户消息（命中高危词：过敏，提前投递）"`

⚠️ **不要**写成纯技术标记（如 `【高危预警触发】`）—— 该字段**不进** `episode_body`，但它是**给人和前端看**的语义字段（库里实测值为 `"验收-初始偏好"` / `"验收-改口"`），塞技术标记会污染语义。人话式写法保留全部审计能力，还顺带记录了**是哪个词命中的**。★ rev.2：**`name` 已被征用为「确认锚点」**（`"cm_" + chatMessageId`，见 §3.5.3；实测可写入、可持久、`/episodes` 可返回），因此 `source_description` **专心写人话**，不要再往里塞标记。

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
2. ⚠️ **`uuid` 由我们指定 → 幂等有抓手** —— ★ **rev.2 实测证伪，见 §3.5**：`uuid != null` 的语义是「**update 一个已存在 episode**」，新建时传 uuid **必然 `NodeNotFoundError` 且静默什么都不写**。**定稿：写入一律传 `null`，确认锚点改用 `name`。**
3. ✅ **一次 POST 带 N 条 = N 次串行 `add_episode`**（单协程 worker），每条 20~45s（实测）→ 见 §3.9 的 `N=1` 决策。
4. ✅ **批内消息顺序不影响裁决结果**（源码推理，B1 验证）：`resolve_edge_contradictions`（`edge_operations.py:544-573`）比的是 **`valid_at`**；`edge_operations.py:838-840` 的判断条件也是 `candidate_valid_at_utc > resolved_edge_valid_at_utc`。**`valid_at` 更晚的那条恒胜，与处理顺序无关。**
5. → **写入粒度确定：一轮 = 一条 message，只投 user。** 不做「整批合成一个 episode」—— 那会让同一批次内的改口失去裁决依据。

### 3.5 ★★ 幂等（rev.2 按**实机实测**重写 —— 原「uuid 幂等抓手」**已被证伪**）

#### 3.5.1 实机实测结论（2026-10-12，隔离 group，用完即删）

**触发原因**：铁律 1 明确要求「在写任何复杂逻辑前，先向测试库投递两次相同 UUID，实测 `add_episode` 的原生幂等性」。
**结果：推翻了 rev.1 的核心假设，并挖出一条全新的静默丢数据路径。**

**源码定位（决定性）** —— `graphiti_core/graphiti.py:1154-1166`（`add_episode` 内）：

```python
episode = (
    await EpisodicNode.get_by_uuid(driver, uuid)   # ← 语义是「取【已存在】的 episode」
    if uuid is not None
    else EpisodicNode(...)                          # ← 新建
)
```

而 `EpisodicNode.get_by_uuid`（`driver/neo4j/operations/episode_node_ops.py:140-156`）：

```python
if len(episodes) == 0:
    raise NodeNotFoundError(uuid)                   # ← 查不到就抛
```

**实机三组对照**：

| 组 | 投递方式 | HTTP | 容器日志 | 图谱结果 | 结论 |
|---|---|---|---|---|---|
| **①** | `uuid = "idem_{ts}_a"`（**一个全新 uuid**） | **202** ✅ | `Background job failed: NodeNotFoundError: node idem_{ts}_a not found` | **什么都没写**（Episodic / RELATES_TO 240s 内零变化） | ⚠️ **新建 episode 绝不能传 uuid** |
| **②** | **不传 uuid** | 202 | 正常 | **27.0s 落库**；Episodic=1 / Entity=3 / edges=2 | ✅ 正确写法 |
| **③** | `uuid = <②自动生成的那个 uuid>`（**已存在**） | 202 | **无任何报错** | Episodic 仍 = 1、edges 仍 = 2（120s 零变化） | ✅ **`uuid=<已存在>` = update 语义**，不产生重复 episode |

**`/episodes` 返回体实测字段**（`GET /episodes/{group}?last_n=N`）：

```
['content', 'created_at', 'entity_edges', 'episode_metadata', 'group_id',
 'labels', 'name', 'source', 'source_description', 'uuid', 'valid_at']
```

#### 3.5.2 三条必须记住的语义事实

| # | 事实 | 后果 |
|---|---|---|
| **1** | **`uuid != null` 的语义是「update 一个已存在的 episode」，不是「用这个 id 新建」** | 新消息传 uuid → **`NodeNotFoundError` → 整个 job 静默失败 → 什么都不写**，而 **HTTP 早已返回 202** |
| **2** | **不传 uuid 时，uuid 由服务端随机生成**（实测 `6e17b7bb-0178-4071-ac10-26dd468ae783`） | 事前**无法知道** episode uuid → 原设计「按 uuid 比对确认落库」**做不了** |
| **3** | **`episode_metadata` 虽存在且返回，但 REST 写不进去**（实测恒为 `None`） | 无法用它做自定义标记 |

⚠️ **这是一条全新的「静默丢数据」路径，而且只在 rev.1 的设计下才会发生**（rev.1 恰好要求传确定性 uuid）。
**若不做这次实测直接进 B1，线上会 100% 丢数据、且无任何告警。** —— 这就是「先做前置验证」的价值。

#### 3.5.3 定稿方案（rev.2）

**写入与确认**：

| 项 | 定稿 |
|---|---|
| `uuid` | ★ **一律传 `null`**。任何"给消息一个自定 uuid"的写法都是错的 |
| **确认锚点** | ★ 改用 **`name` 字段承载确定性标记**：`name = "cm_" + chatMessageId`。实测 `name` **能写入、能持久、`/episodes` 能返回**（且默认空串、完全空闲） |
| 确认方式 | `GET /episodes/{group}?last_n=N` → 按 **`name`** 匹配（不再按 uuid） |
| ★ 学习并回填 | 确认命中时**顺带把服务端生成的 `uuid` 落库**到 `chat_message.graphiti_episode_uuid`（同一个响应里就有，**零额外请求**）。用途：① 遗忘权的 `DELETE /episode/{uuid}`；② 记忆溯源；③ 将来若要用 update 语义重投 |

**幂等防线（rev.2 修正）**：

| 层 | 机制 | 定位 |
|---|---|---|
| ~~第一层：`uuid` = `"cm_"+id`~~ | ❌ **已证伪并删除** | 见 §3.5.1 —— 会让新消息**必然失败** |
| **第一层：`name` = `"cm_"+id`** | 服务端**不**据此去重（实测无原生去重），但它是**我们的确认锚点** | 对账抓手 |
| **第二层 → 升级为唯一防线：`graphiti_dispatched_at` 的 DB CAS 占位 + 可重试失败回滚** | ★ **rev.2 起它不再是「第二层」，而是「唯一防线」** | §3.3 / §3.8 |
| ~~Redis `setnx`~~ | ❌ 已删除（补丁 1） | 见下 |

**★ 关键结论（必须写进代码注释）**：
**Graphiti 服务端对「同一条消息被重复投递」没有任何原生去重能力**（uuid 不可预指定、内容也不去重）。
→ **「绝不重复投递」的责任 100% 落在我们自己的 DB CAS 上。**
→ **铁律 1 由「重要」升级为「唯一」。**

**为什么删掉 Redis setnx 层（理由不变，但因实测而更关键）**：

1. **死锁**：TTL 与「可重试」本质冲突 —— 消息 30s 后回来、锁还在 → 被自己的锁**永久拒收**。评审指出的那颗雷；`del` 只是打补丁，**删掉更彻底**。
2. **权威性矛盾**：§3.8 已论证「Redis 不是权威源」，此处却用它做防重 —— **同一份文档两套标准**。
3. **零收益**：CAS 占位已提供完整防重（含「投递中」窗口的自动超时放开），Redis 层**不增加任何能力**。

#### 3.5.4 附带确认：`/episodes` 的排序键 = `created_at`（落库时刻）

**实测**（同一 group 投两条，业务时间相隔 90 天，先投"今天"再投"90 天前"）：

```
[0] name=cm_B_...  created_at=2026-10-12T14:56:21.374219Z  valid_at=2026-07-12T10:00:00Z
[1] name=cm_A_...  created_at=2026-10-12T14:56:06.029097Z  valid_at=2026-10-12T10:00:00Z
```

→ **index 0 是「更晚落库」的那条，尽管它的业务时间是 3 个月前。**
→ ✅ **排序键 = `created_at`（落库时刻）** ⇒ §3.6.1 的「投递时间窗」公式**成立**（历史补投的消息会排在列表**最前面**，窗口罩得住）。

### 3.6 ★ 落库确认：**202 ≠ 成功**，且**确认不能阻塞消费者**（纠正 v2 的 S1）

`POST /messages` 返回的 202 只代表「进了 `asyncio.Queue`」。首条 episode 实测 **21~45s** 才进 Neo4j。

| 策略 | 评价 |
|---|---|
| S0 收到 202 就 ack（不确认） | ❌ 后台 worker 失败时我们**完全不知道** → 重试机制形同虚设 |
| ~~S1 消费者内轮询 `GET /episodes` 按 uuid 比对~~ | ❌ **v2 的 S1 已废弃** —— 见下方「为什么废弃」 |
| **S2 投递即 ack + 独立定时对账（定稿）** | ✅ **推荐** |
| S3 回调 | ❌ Graphiti 无 webhook 能力 |

**为什么废弃 v2 的 S1**：S1 在**消费者线程内**轮询（90s 窗口、5s 间隔）。即使 `N=1`，每条也要阻塞最多 90s；而一次扫描可能有 10 条待投 → **消费者被占住 15 分钟无法 ack** → RabbitMQ 会认为消费者假死、`consumer_timeout` 可能直接杀连接。**这是不能接受的。**

**S2 的完整形态（rev.2：拆成两个职责单一的任务 + 动态 `last_n` + 失败上限）**：

```
MQ 消费者：CAS 占位 → POST /messages → 收 202 → 立即 ack（耗时 <1s，永不阻塞）

━━ GraphitiConfirmJob（每 5 分钟）—— 只管「已投递、但没确认落库」━━━━━━━━━━━━━━
  ① 目标集（★ 暗雷 1 + 暗雷 3 的核心修复）
       WHERE graphiti_synced_at IS NULL
         AND graphiti_dispatched_at IS NOT NULL          ← ★ 只看"已投递"（判否消息天然被排除）
         AND graphiti_dispatched_at < now() - 3min        ← 覆盖 202→落库 的确认窗
         AND NOT EXISTS(SELECT 1 FROM graphiti_write_audit a WHERE a.message_id = c.id)
                                                          ← ★ 第二道防线（铁律 2 强制）
  ② 动态窗口（★ 补丁 3，公式见 §3.6.1 —— 按【投递时间窗】算，不再是 chat_message.created_at）
  ③ GET /episodes/{groupId}?last_n=<动态值> → 按【name = "cm_" + chatMessageId】比对
                                               ★ rev.2：不再按 uuid（服务端 uuid 事前不可知，§3.5.2）
  ④ 命中   → UPDATE synced_at = now,
                    graphiti_episode_uuid = 该 episode 的 uuid   ← ★ rev.2 顺带回填（零额外请求）
     未命中 → UPDATE confirm_rounds = confirm_rounds + 1
              rounds <= 2           → 只计数、【不重投】
                                      （落库本身 20~45s；且可能只是慢 —— 别和落库延迟赛跑）
              3 <= rounds < MAX(8)  → 重投（dispatched_at 置 NULL → 投 write.queue）
              rounds >= MAX         → ★ 写入审计表(reason=CONFIRM_TIMEOUT) + 停止重投   ← 防无限重投
  ⑤ 每轮输出指标：重投条数 / 隔离条数 / 未确认积压数（喂 §5.4）

━━ GraphitiReplayJob（每 10 分钟）—— 只管「该投但没投出去」━━━━━━━━━━━━━━━━━━
  ① 目标集  WHERE graphiti_synced_at IS NULL
              AND graphiti_dispatched_at IS NULL          ← ★ 暗雷 1 的正面修复
              AND NOT EXISTS(审计表)
              AND created_at < now() - 5min               ← 避开正被 GraphitiScanConsumer 处理的消息
  ② 逐条投 graphiti.write.queue（priority = 1）
  ← 它是「熔断期被跳过」「scan 崩溃漏投」两类数据的唯一补投责任人（铁律 4：只延迟不丢弃）
  ← 它【不会】把判否的垃圾捞回来：判否的已进审计表，被 NOT EXISTS 排除
```

⚠️ **为什么 `dispatched_at IS NULL` 绝不能出现在对账任务的扫描条件里**（暗雷 1 的完整因果链）：

| 消息类型 | `dispatched_at` | `synced_at` | 旧 SQL（`IS NULL OR < now-3min`）会怎样 | 新设计 |
|---|---|---|---|---|
| **判否的闲聊**（审计表） | NULL | NULL | ⚠️ **满足条件** → 被当成「漏网之鱼」→ **强制重投进图谱**（前门拦、后门灌） | 不在 Confirm 视野（看 `IS NOT NULL`）；且被 Replay 的 `NOT EXISTS` 排除 |
| **熔断期被跳过的** | NULL | NULL | ⚠️ 同上 → **绕过熔断**强行投递（等于熔断失效） | 不在 Confirm 视野；由 Replay 在**熔断解除后**补投 |
| **投递中** | 非空(<3min) | NULL | 正确排除 | 正确排除 |
| **投递超时** | 非空(>3min) | NULL | 正确重投 | 正确重投（并计入 `confirm_rounds`） |

**一句话**：`dispatched_at IS NULL` 有**三种**互不相同的语义（判否 / 被熔断跳过 / 待扫描），把三者塞进同一个 WHERE 是暗雷 1 的根因。**新设计用「任务拆分 + 审计表」给三者各自归属。**

⚠️ **`confirm_rounds` 是新增列**（`chat_message` 第三列）：没有它，「永远落不了库」的消息会被**无限重投** —— 评审只修了「窗口不够大」，**没修「窗口够大也永远找不到」的情形**。上限 `MAX(8)` ≈ 40 分钟，超限即隔离进审计表 + 告警（人工可查、可手动补投）。

### 3.6.1 ★ 补丁 3：`last_n` 必须动态计算（否则会「无限重投」）

**雷区**：`GET /episodes/{groupId}?last_n=K` 是**纯时间倒序截断、无 offset/无游标**（已实测）。若把 `K` 写成常数（如 10）：

> 用户在 5 分钟内连发 30 条短消息 → 目标 uuid 被挤到第 31 位 → `last_n=10` 的窗口**罩不住它** → 对账误判「投递失败」→ 重投 → 又「失败」→ **死循环重投**，Neo4j 里塞满重复 episode。

**这不是概率问题**：只要某个确认周期内的消息量 > K，它就**必然**发生。K 越小越容易触发。

**定稿（rev.2 修正）：窗口必须覆盖「比最老目标更新的所有 episode」，而不是「未确认数」**

⚠️ **第一轮补丁 3 的公式有两个洞**（本轮修复）：

| 洞 | 表现 |
|---|---|
| **洞 1：量纲取错** | 用 `chat_message.created_at` 算 —— **历史补投**（半天宕机后修复）与**长重试链**（>10min 才落库）的消息 `created_at` 很旧 → `X` 极小甚至 0 → `last_n` 取保底 50 → **200 条补投时罩不住** |
| **洞 2：取「未确认数」也不够** | 一条卡住 2 小时、其间正常落了 200 条 → 未确认数 ≈ 1 → `last_n = 12` → **那条永远找不到** → 无限重投（正是本补丁要修的雷） |

**正确量纲**：`/episodes` 按**落库时间**倒序（≈ 投递顺序），所以「比最老目标更新的 episode 数」= **在 `[最老目标的 dispatched_at, now]` 这个时间窗内被投递出去的消息数**。

```sql
-- ① 最老的目标（已投递、未确认、未隔离）
SELECT MIN(graphiti_dispatched_at) INTO :oldestTarget
  FROM chat_message c
 WHERE c.graphiti_synced_at IS NULL
   AND c.graphiti_dispatched_at IS NOT NULL
   AND c.graphiti_dispatched_at < now() - INTERVAL 3 MINUTE
   AND NOT EXISTS (SELECT 1 FROM graphiti_write_audit a WHERE a.message_id = c.id);

-- ② 该窗口内【已投递】的消息数（= 会排在目标之上的 episode 数上界）
SELECT COUNT(*) INTO :X
  FROM chat_message
 WHERE graphiti_dispatched_at IS NOT NULL
   AND graphiti_dispatched_at >= :oldestTarget
   AND graphiti_dispatched_at <= now();

last_n = max(50, X + 10)
          ▲ 保底 50 ：目标为空 / 只有一两条时也不至于取太小（服务端成本可控）
          ▲ +10     ：并发写入 + 落库滞后的缓冲
          ▲ 封顶 500：超过则打 WARN（说明窗口设错或有异常流量）
```

**为什么这版同时覆盖三种场景**：

| 场景 | `oldestTarget` | `X` | 结论 |
|---|---|---|---|
| **稳态**（~2 条/分钟） | ≈ 3 分钟前 | ≈ 6 | `last_n = 50` 足够 |
| **历史补投 200 条** | = **刚刚**（重投时 `dispatched_at` 被刷成 now） | ≈ 200 | `last_n = 210` → **罩得住** ✅ |
| **一条卡住 2 小时 + 其间正常 200 条** | = 2 小时前 | ≈ 200+ | 覆盖整个 2 小时 → ✅ |

✅ **前提已实测确认（2026-10-12）**：`/episodes` 的**排序键 = `created_at`（落库时刻）**，**不是** `valid_at`。
证据见 §3.5.4：业务时间为 90 天前的那条，因**落库更晚**而排在 index 0。

⇒ **历史补投的消息会排在列表最前面，窗口罩得住** → 上面的公式**成立**。

（⚠️ 若未来上游把排序键改成 `valid_at`，本公式会**静默失效** → 故附录 D #2b 保留为「版本升级后的回归项」，不删除。）

**三条仍然有效的边界**：

| # | 边界 | 处理 |
|---|---|---|
| 1 | `X` 异常大（如 > 1000）→ `last_n` 巨大可能拖慢服务端 | 封顶 `min(calc, 500)`，超限打 WARN。出现即说明窗口设错或有异常流量 |
| 2 | ⚠️ 这是**「用消息数近似 episode 数」**，两者并非严格相等 | 所以留 `+10` 余量，而**不是**精确等于 —— **本补丁求的是「窗口足够大」，不是「窗口刚好」** |
| 3 | ⚠️ 与 `/episodes` 的**服务端成本**呈线性 | 每 5 分钟一次、单组，成本可接受；**不要**做成「每个用户每分钟一次」 |

⚠️ **备选（B1 实测后择优）**：`/episodes` 无时间过滤能力（未知参数被静默忽略，已实测），所以只能靠 `last_n` 大。若实测「固定大值 `last_n=200` 的响应时间与体积可接受」，可**退化为固定大值 + 客户端 uuid 过滤** —— **比动态计算更简单、且不会算错**。但**必须先实测**（附录 D #8）。

**S2 的三个附带收益**：① 消费者永不阻塞；② 重试发生在对账任务里，消费者线程干净；③ `dispatched_at`/`synced_at` 是 MySQL 里的**审计事实**，一条 SQL 就能回答「哪些投了没落库」——直接喂 P7 的 DLQ/健康度监控。

⚠️ `/episodes` 返回的是 graphiti-core 原始 `EpisodicNode` 列表（**不是 DTO**）。**已实测字段**：
`content` / `created_at` / `entity_edges` / `episode_metadata`（恒 `None`）/ `group_id` / `labels` / **`name`** / `source` / **`source_description`** / **`uuid`** / `valid_at`。
时间格式是 `...Z`（**与 `/search` 的 `+00:00` 不同**，`OffsetDateTime` 两者都能吃）。**排序键 = `created_at`（落库时刻）**，已实测（§3.5.4）。
⚠️ `last_n` 是**倒序截断、无游标**。→ 这是我们把 `name` 选作确认锚点的原因。

### 3.7 重试分类（决定退避曲线）—— 补丁 1 + 补丁 4 已重构

| 情况 | 判定 | 动作 | **是否回滚占位** |
|---|---|---|---|
| 400 / 422 | **毒消息**（载荷错误，重试无意义） | 进 DLQ **并且**写审计表 `reason=POISON`（★ rev.2 修正） | ❌ **保留** |
| **429 / 403（配额耗尽）** | ★（补丁 4）**限流 —— 绝不是毒消息** | **直接进 2min 或 10min 档**（跳过 30s） | ✅ 回滚 |
| 连接拒绝 / 超时 / 5xx / 502 | 可重试 | 30s → 2min → 10min，三次后 DLQ **+ 写审计表 `reason=RETRY_EXHAUSTED`** | ✅ 回滚 |
| 对账确认超时 | 可重试（不是毒消息） | 由 `GraphitiConfirmJob` 重投；超 `MAX(8)` 轮 → 审计表 `reason=CONFIRM_TIMEOUT` | （走对账路径，见 §3.6） |

**重试计数承载方式**：消息体加 `attempt`（int），消费者按 `attempt` 决定投哪个重试队列；`attempt >= 3` → DLQ。

★ **「是否回滚占位」是补丁 1 的关键列**：**除毒消息外，一切可重试/未预期异常都必须回滚** `dispatched_at = NULL`；否则消息会被自己的防重机制永久拒收（§3.3）。

#### 3.7.2 ★ rev.2 修掉的第四颗洞：**毒消息与对账任务的「互踢」**

**这颗洞是补丁 1 自己引入的，第二轮评审也没发现** —— 但它会让 rev.1 的表「保留占位（避免坏消息无限重投）」的意图**完全落空**：

```
毒消息(400/422) → 【保留占位】dispatched_at = 非空、synced_at = NULL → 进 DLQ
                                     │
                                     ▼
         GraphitiConfirmJob 扫到它：synced_at IS NULL AND dispatched_at IS NOT NULL
                                     AND dispatched_at < now-3min  ✅ 命中
                                     │
                                     ▼
              在 /episodes 里永远找不到它 → 判「投递失败」→ 重投
                                     │
                                     ▼
                       payload 依旧畸形 → 又 400 → 又保留占位 → 又进 DLQ → 无限互踢
```

**→ rev.2 修复：毒消息必须【同时】写审计表（`reason=POISON`）。**

**这一步把审计表的语义收敛成一句话**：

> **审计表 = 所有「永不（重）投递」的终态集合**（判否 / 毒消息 / 重试耗尽 / 对账超限），
> 因此 **Confirm 与 Replay 两条 SQL 的 `NOT EXISTS(审计表)` 同时挡住了「垃圾入图」和「坏消息互踢」两类死循环。**

⭐ **运维红利（白捡）**：审计表带 `reason` → **人工恢复路径**变成「删掉那一行审计记录」即可重新纳入投递（`dispatched_at` 已为 NULL 的由 ReplayJob 接管）。**不需要写任何恢复工具。**

⚠️ **一个必须承认的极限**：若外部故障持续超过「最大重试总窗」（30s + 2min + 10min ≈ 12.5min），消息会耗尽重试 → DLQ + 审计终态。**这正是背压熔断存在的意义** —— 熔断必须在耗尽之前就冻结生产（§3.10）。→ **熔断阈值必须显著小于「最大重试窗内能被消费掉的消息量」。**

★ **优先级保全（补丁 6 的配套，评审提出）**：`priority` 是**消息属性**，不是队列属性。
- 凡是**我们自己的代码构造 `Message` 重新发布**的地方（消费者 → `retry.*`、`GraphitiReplayJob` → `write.queue`），**必须显式 `setPriority(原优先级)`**——不做，高危消息**失败一次就降级成平民**，优先级队列形同虚设。
- 而 `retry.*` → `write.queue` 那一跳由 **broker 的 TTL+DLX 代劳**，属性是否保留**必须实测**（附录 D #11），**不能假设**。

### 3.7.1 ★ 补丁 4：429 必须显式拦截（业务级灾难）

**雷区**：Graphiti 后面挂 DashScope。**限流（429）在晚高峰是常态**。若按「4xx = 毒消息」一刀切：

> 用户的新偏好**成批地、永久地**丢失 —— 不是「延迟」，是**再也回不来**（DLQ 语义 = 人工介入）。

**定稿：显式识别限流，走最长退避**

| 码 | 归类 | 退避 |
|---|---|---|
| `429` | **限流，可重试** | **跳过 30s**，直接 `2min` → `10min`（避开持续撞墙） |
| `403` + 响应体含 `quota` / `exceeded` / `Throttling` | **配额耗尽，可重试** | 同上 |
| 其余 4xx | 毒消息 | DLQ |

⚠️ **判定不能只看状态码，必须同时看响应体**：

- Graphiti 服务端**可能把 DashScope 的 429 包装成 500**（内部异常 → FastAPI 默认 500）→ 「5xx 一律可重试」这条已兜住它，**不需要额外处理**。
- 需要显式拦截的是：**Graphiti 透传出来的 429/403**，以及**响应体含 `rate limit` / `Throttling` / `quota` 关键词**的任何 4xx。
- → 实现建议：一个 `isRetryable(...)` 判定函数，**先看码、再看 body 关键词**；**不要**写成 `status / 100 == 4` 这种一刀切。

⚠️ **一个异步架构下的盲区（必须知道）**：`POST /messages` 返回 202 后，**真正的 qwen 调用发生在 Graphiti 后台 worker 里**。若那里触发 429：

- Java 侧**看不到 429**，只会看到「**对账确认超时**」；
- → 这条失败**不走本节 DLQ 路径**，而走 §3.6 的对账重投路径；
- → **所以「对账超时重投」也必须长退避**（连续 N 轮未确认 → WARN；重投之间 ≥ 5min 间隔，避免把 Graphiti 打死）。

**→ 推论：429 的防御在「两处」而不是一处** —— 投递层（本节）+ 对账层（§3.6）。

★ **不要用 `@Retryable` 做 MQ 重试**：它在**同一线程内循环**，重试期间消息未 ack、消费者被占住；而 TTL+DLX 把重试**卸载到 broker**，消费者线程立即释放。两者语义不同，别混用。（`@EnableRetry` 在工程里已开，但**不要用在这条链上**。）

### 3.8 ★ 投递状态：双字段状态机（P8b）

**为什么必须双字段** —— `synced_at` 的写入时机是个二选一困境：

| 时机 | 后果 |
|---|---|
| **收到 202 就写 `synced_at`** | worker 后续静默失败 → 该消息永远不会被重投，**永久丢失** |
| **确认落库后才写 `synced_at`** | 20~45s（甚至 90s+）的确认期内它仍是「未同步」 → **下一轮扫描会重复捞到它** → 重复投递 |

**定稿：两个字段，三段状态（补丁 1 后，`dispatched_at` 兼作互斥锁）**

| 状态 | `dispatched_at` | `synced_at` | 含义 | 谁能改它 |
|---|---|---|---|---|
| **未投递** | NULL | NULL | 待扫描投递 | — |
| **投递中** | 非空（< 3min） | NULL | ★ **CAS 占位已获得**，已交 Graphiti、等落库；**扫描 / 对账 / 其它消费者都不许重投** | 消费者（占位）；可重试异常**必须回滚** |
| **已落库** | 非空 | 非空 | 终态 | 对账任务 |
| （例外）**投递超时** | 非空（> 3min） | NULL | 占位未回滚且超时 → **自动放开**，允许重新占位 | 任意消费者 / 对账任务 |

★ **`dispatched_at` 的「双重角色」（补丁 1 的核心）**：它既是**审计事实**（何时投的），又是**互斥锁**（CAS 占位的凭证）。两个角色由同一条
`UPDATE ... WHERE dispatched_at IS NULL OR dispatched_at < now-3min` 胜任 —— **不需要 Redis 参与**。

⚠️ **一句必须记住的话**：**「投递中」是一个「可超时自动释放的软锁」，不是永久标记。** 若它不可超时，就退化成 Redis setnx 的 1h 死锁 —— **补丁 1 修的正是这个。**

```sql
ALTER TABLE chat_message
  ADD COLUMN graphiti_dispatched_at DATETIME NULL COMMENT '投递到 Graphiti 的时刻（CAS 占位成功时写入）',
  ADD COLUMN graphiti_synced_at     DATETIME NULL COMMENT '确认落库到图谱的时刻（对账任务写入）',
  ADD COLUMN graphiti_confirm_rounds INT NOT NULL DEFAULT 0
      COMMENT '★ rev.2 新增：对账未命中的累计轮次；达上限即隔离，防无限重投',
  ADD COLUMN graphiti_episode_uuid VARCHAR(64) NULL
      COMMENT '★ rev.2 新增：确认命中时回填的服务端 episode uuid（供遗忘权 DELETE /episode/{uuid} 与记忆溯源）',
  ADD INDEX idx_graphiti_pending (graphiti_synced_at, graphiti_dispatched_at),
  ADD INDEX idx_graphiti_episode_uuid (graphiti_episode_uuid);
```

⚠️ **`graphiti_episode_uuid` 为什么必需**（rev.2 新增）：服务端 uuid **事前不可知**（§3.5.2），而 `DELETE /episode/{uuid}`（遗忘权，B3）与「记忆溯源」（P1-附）都需要它；`/episodes` **无过滤、无游标** → 事后去列表里翻是**不可靠**的。→ **在确认命中的那一次响应里顺手落库**，零额外成本。

⚠️ **`graphiti_confirm_rounds` 为什么必需**（rev.2 新增）：
`last_n` 算得再准，也有「**窗口足够大、但那条消息就是永远落不了库**」的情形（Graphiti 侧静默失败 / uuid 从未被写入）。此时对账任务会**每 5 分钟重投一次、永远重投下去** —— 这**恰好就是暗雷 3 要防的无限重投**，而评审的补丁只解决了「窗口不够大」那一半。**上限 + 隔离是另外那一半。**

**为什么不用 Redis 锁替代**：Redis 不是权威源（重启/淘汰就丢），而「投递状态」是写链路的**审计事实**，应该落在 MySQL 里（可 SQL 直查、可补投、可审计）。

**为什么不用 `graphiti_synced_at` 单字段 + Redis `setnx` 防重捞**：单靠 Redis 锁，一旦锁丢失就会重复捞取；双字段是**幂等的、可自愈的**（`dispatched_at` 超时自动放开），不依赖任何易失状态。

⚠️ **连带修改**：`fetchRecentUserTurns()` 的 WHERE 必须同步改为
`graphiti_synced_at IS NULL AND graphiti_dispatched_at IS NULL AND NOT EXISTS(审计表)`
（并叠加水位与 `created_at < now()-5min` 语义）。否则 `N=1` + 单字段会重复捞取。

⚠️ **★ rev.2 必须牢记的一句话（暗雷 1 的根因）**：
**`graphiti_dispatched_at IS NULL` 有且不只有一种含义** —— 它有**三种**互不相同的语义：

| `dispatched_at IS NULL` 的真实含义 | 谁负责它 |
|---|---|
| ① **尚未投递**（正常待投 / 熔断期被跳过 / scan 崩溃漏投） | `GraphitiScanConsumer`、`GraphitiReplayJob` |
| ② **被 `MemorySignalJudge` 判否**（永不投递，已写审计表） | 无人 —— **终态** |
| ③ **毒消息 / 重试耗尽**（已写审计表） | 无人 —— **终态** |

→ **任何把「`dispatched_at IS NULL`」直接当作「待投递」的 SQL 都是错的。** 必须与审计表联合判断。
→ 反过来，**对账任务（Confirm）只允许看「已投递」（`dispatched_at IS NOT NULL`）** —— 这样它连犯错的入口都没有。

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

### 3.10 ★ 补丁 6 + 7：吞吐量触顶时的防雪崩（优先级 + 背压熔断）

**先摆清物理极限（改不了）**：Graphiti 侧 `AsyncWorker` 是**单协程串行**处理 `asyncio.Queue`，每条 `add_episode` 实测 **20~45s** → **消费速率上限 ≈ 2 条/分钟**。

**这暴露了「快通道」的一个被忽视的真相**：它只绕过了前置的 5 分钟 delay 队列；**若 `graphiti.write.queue` 里已积压 10 条，高危消息依然要排队**（补丁 6 的优先级队列正是为此）。

同理，`N=1` 也**不能**解决「生产速率 > 消费速率」的**绝对积压**。

**在不魔改 Python 服务端的前提下，能做两件事：**

#### (a) 补丁 6：优先级队列（`x-max-priority = 10`）

详见 §3.2 设计点 6。要点：

- 常规 `priority = 1`，高危快通道 `priority = 10`；
- **只有队列积压时才生效**（空闲时消息一到就被推走）；
- 单消费者串行 → 插队只保证「高危排在所有常规消息之前」，**不免除 45s 的处理时间**；
- ⚠️ 队列参数变更会导致 406，需先删队列重建（§7.4）。

#### (b) 补丁 7：背压熔断（`GraphitiBackpressureGuard`）

**目标**：当 Graphiti 处理能力**已触顶**时，主动**暂停常规投递**，把有限的消费能力留给**高危事实** —— 以「普通记忆延后」为代价保住核心链路。

| 项 | 设计 |
|---|---|
| **信号源（★ rev.2 修正）** | **仅 MQ 物理积压** = `write.queue.ready` + `retry.30s.ready` + `retry.2min.ready` + `retry.10min.ready` 之和。<br>获取：`RabbitAdmin.getQueueInfo(name).getMessageCount()`（需 catch「队列不存在」）。<br>⚠️ **量纲诚实说明**：AMQP 被动声明只返回 **ready**（不含 unacked）；因 `concurrency=1 / prefetch=1`，unacked ≤ 1，可忽略。<br>❌ **不含** `scan.delay.queue`（它**恒定**有 ~N 条在设计延时中，纳入会让基线噪声淹没信号）。❌ **不含** DLQ。 |
| **触发** | 积压之和 > `graphiti.backpressure.queue-depth-threshold`（默认 **50**） |
| **辅助观测（★ 不参与触发）** | `未确认数 = COUNT(*) WHERE dispatched_at < now()-15min AND synced_at IS NULL` —— **只作告警与看板**。 |
| **动作** | `GraphitiScanConsumer` ⑤ 步直接 ack 返回（**不投递常规消息**）；**高危快通道仍放行** |
| **恢复** | 积压之和 < 阈值 × 0.5 **且持续 3 分钟** → 自动解冻 |
| **被跳过数据的归属** | `dispatched_at` 保持 NULL → 解冻后由 **`GraphitiReplayJob`** 补投（铁律 4） |
| **评估频率** | 每 30s 一次（轻量，四次队列查询） |

**⚠️ ★★ 暗雷 2：熔断信号的第二次修正（三轮演化，一次比一次接近物理事实）**

| 轮次 | 信号 | 为什么不够 |
|---|---|---|
| 第一轮评审 | **DLQ 深度** | DLQ 只在**重试全部失败后**才涨 —— 等它涨，**数据已经丢了**，反应太慢 |
| v3-Final rev.1 | 主队列积压 **+ 未确认数** | ⚠️ **未确认数是 MySQL 软状态，会被我们自己的回滚逻辑清零**：补丁 1 规定可重试失败要回滚 `dispatched_at = NULL`；Graphiti 全挂时**每一条都被回滚** → 未确认数 **≈ 0** → **熔断器报「系统无比健康」，继续猛灌** |
| **rev.2（定稿）** | **仅 MQ 物理积压** | 回滚 `dispatched_at` **不改变消息在 MQ 里的位置**。Graphiti 全挂时消息**必然**堆积在 `retry.*` 三档里循环 → 积压之和**单调上涨**，**不可欺骗** |

**★ 一句话记住这个修正**：**用「消息在哪个物理容器里」判断健康，不用「数据库里某个字段的值」判断健康。** 前者是物理事实，后者是会被我们自己改写的软状态。

**另两处对评审原方案的修正（仍然有效）**：

| # | 评审原方案 | **本版** | 理由 |
|---|---|---|---|
| 2 | 「以**丢弃**普通记忆为代价」 | **「延迟投递」而非丢弃** | 被跳过的消息 `dispatched_at` 保持 NULL → 由 **`GraphitiReplayJob`** 在解冻后补投。**这个「补投责任人」是 rev.2 才补上的** —— 没有它，「延迟」会静默退化成「永久不投」（即事实上等于丢弃） |
| 3 | 未定义恢复条件 | **阈值 × 0.5 且持续 3min** | 无恢复条件 = 熔断后不自动恢复、需人工介入 —— 那是「故障」不是「保护」 |

⚠️ **熔断必须可观测**：触发/恢复都打 WARN + Micrometer（`graphiti.backpressure.active` 作 Gauge），并进 §5 健康度看板。**静默的熔断 = 静默的数据延迟。**

⚠️ **熔断与高危快通道的关系**：熔断**只冻结常规路径**（§3.3 的 ⑤ 步）。高危词表命中的消息在 `ChatStreamService` 侧**直接投递**、不经过 `GraphitiScanConsumer`，所以天然不受熔断影响 —— 这正是「保核心」的含义。

⚠️ **一个仍存在的极限（如实说）**：若 Graphiti 彻底宕机，高危消息也会积压在 `graphiti.write.queue`（priority=10 排在队头）—— **背压保的是「优先级」，不是「可用性」**。可用性靠 §2.5 超时降级 + §4.3 静默降级。

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

> 双通道已拍板（§0.3）。补丁 2 / 补丁 5 已并入本节。

```
用户提问
  │
  ├─(已有) L1  Redis 窗口原文 + ChatSummaryService 摘要            ── 0 网络开销
  │
  ├─(新增) L2-a 画像召回  POST /search {query: <模板化固定句，见下>,
  │                                     group_ids: ["user_{id}"], max_facts: 20}  ← limit5 × FETCH_FACTOR4
  ├─(新增) L2-b 情景召回  POST /search {query: <用户本轮原话>,
  │                                     group_ids: ["user_{id}"], max_facts: 20}  ← 同上（补丁2）
  │        两路并行发起；硬预算 800ms；超时 → 该路视为空（绝不阻塞首字）
  │        ★ 两路均【不缓存】（见下「为什么撤回缓存」）
  │
  ├─(新增) L2-c 回忆召回（P1-新，仅当意图 = 回忆类）
  │        Qdrant conversation_archive 检索（稠密+稀疏 RRF）；命中的原话
  │        进入独立块，带出处与时间，标注「历史原话，不代表当前事实」
  │
  └─(已有) L3  Qdrant dynamic_brain 双路并发（本地知识库 / 全网）  ── 已有 4s 就绪预算
  │
  ▼ Java 侧统一收口（GraphitiMemoryRecaller）★补丁5：两路【独立过滤、独立截断】
    画像路：20 条候选 →（invalidAt 过滤 + sanitizeFact 已在 Client 内完成）→ 取 Top-5 → 画像桶
    情景路：20 条候选 →（同上）                                              → 取 Top-5 → 情景桶
    ⚠️ 【严禁】两路合并后按 validAt 全局倒排再截断（见 §4.2.1）
    跨路仅做一次 uuid 去重（同一条 fact 同时命中两路时保留其"画像路"身份，不改变各自桶内排序）
  │
  ▼ 组装 System Prompt
    {{userGlobalMemory}}  ← 画像桶（块首【当前生效偏好与画像】）
    {{episodicMemory}}    ← 情景桶（带「生效于 YYYY-MM-DD」）
    {{historySummary}}    ← L1 摘要（已有，不动）
    {{recentContext}}     ← L1 原文 + 「未沉淀原文」时间窗（风险二·第 2 条）
    {{context}}           ← L3 资料（已有，块首加声明）
```

### 4.2.1 ★ 补丁 5：禁止「双通道合并后全局排序」—— 防「排序踩踏」

**雷区**：若把两路 fact 合并后统一按 `validAt` 倒排再截断：

> 用户昨天聊的「喜欢吃辣」（`validAt` 很近）**必然**把三年前设定的「我是后端开发」（`validAt` 很久远）**挤出 Top-5**。

**画像被近期闲聊挤掉** —— 而画像恰恰是「常驻注入」要保的东西（P3-补）。

**根因：两条通道的「时间尺度」根本不可比。**

| 通道 | 语义 | 时间特征 | `validAt` 的意义 |
|---|---|---|---|
| 画像 | **长期稳定属性** | 三年前说的「后端开发」**今天依然成立** | **不代表「新旧」，只代表「何时说的」** |
| 情景 | **近期相关事实** | 昨天说的「吃辣」 | 越近越相关 |

→ **`validAt` 倒排对画像通道是「错纬度的排序」**。用一个维度去排两种不同性质的集合，必然「踩踏」。

**定稿：独立过滤 → 独立截断 → 物理分槽**

```
画像路 → 独立过滤 → 按【画像相关性】截断 Top-5 → {{userGlobalMemory}}   （≤300字）
情景路 → 独立过滤 → 按【validAt 倒排】截断 Top-5 → {{episodicMemory}}   （≤400字）
                          ▲ 只有情景路用时间倒排（它确实「越近越相关」）
```

**三条必须遵守的约束**：

| # | 约束 | 说明 |
|---|---|---|
| 1 | **禁止跨通道合并排序** | 两个桶各自排序、各自截断；合并排序 = 本补丁要修的雷 |
| 2 | **物理分槽，不混装** | 画像进 `{{userGlobalMemory}}`、情景进 `{{episodicMemory}}` —— **分槽本身就是结构性防御**（模型不可能用情景挤掉画像，因为不在同一块） |
| 3 | 跨路去重**只去重、不重排** | 同一条 fact 同时命中两路（uuid 相同）时，保留其在「画像路」的身份、从情景路移除；**但不得因此改变两桶内各自的排序** |

⚠️ **与补丁 2 的配合**：两路**各自**先放大到 20 条再过滤再截断（§2.3.1）—— **放大和截断都必须在通道路内完成**；绝不能「先各取 20 → 合并成 40 → 过滤 → 全局截 10」。

⚠️ **槽位预算的连带影响**：若某路过滤后不足 5 条，**就少注入几条**（不要从另一路借）—— 借用即踩踏。

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

→ **结论：影子期的观测对象必须包含「被 ② 判否的原文」。** 这是**判否审计表**存在的理由。★ rev.2 更正：审计表**新建 `graphiti_write_audit`**（不再复用 `user_semantic_memory`，理由见 §7.3）—— 因为它的语义已从「判否日志」扩成「**所有永不（重）投递的终态集合**」（暗雷 1 + §3.7.2）。

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

### 5.4 六个量化验收指标（原四个 + 补丁 2/3/7 各一项）

| 指标 | 定义 | 为什么重要 |
|---|---|---|
| **写入成功率** | 成功落库 message / 应写 message | 低于 99% 说明**链路**有问题（而**不是**抽取质量问题）——先分清是哪一类 |
| **抽取非空率** | 落库 episode 中抽出 ≥1 条 fact 的比例 | ★ **双向信号**：过高 = 判太宽（噪音入图）；过低 = 判太严（漏拦）。**这一个数字就能定位 `MemorySignalJudge` 的门限该往哪调** |
| **重复边率** | 语义相同 (source, relation, target) 的重复边占比 | 验证 `uuid` 幂等 + `dispatched_at` 防重是否真的生效 |
| **孤儿实体率** | 只有一条边、且该边已作废的 Entity 占比 | 图谱垃圾的代理指标；上线越久越该稳定在低水位 |
| ★（补丁 2）**过滤后不足率** | `recallActive` 中「过滤作废后剩余 < limit」的次数占比 | **唯一能告诉我们 `FETCH_FACTOR=4` 够不够用的数字**；> 5% → 上调 |
| ★（补丁 7 + **暗雷 2**）**MQ 积压之和** | `write.queue` + 3 个 `retry.*.queue` 的 **ready 之和** | 背压熔断的**唯一触发信号**。它是**物理事实**，不会被我们自己的回滚逻辑欺骗 |
| ★（**暗雷 2**）**未确认积压数（辅助观测）** | `dispatched_at < now-15min AND synced_at IS NULL` 的计数 | **只作告警与看板，不参与熔断触发** —— 可重试失败会回滚 `dispatched_at`，Graphiti 全挂时它反而趋 0（§3.10） |
| ★（补丁 3）**对账重投次数** | `GraphitiConfirmJob` 每轮的重投条数 | 突增 = 要么 Graphiti 变慢，要么 `last_n` 窗口算错了 |
| ★（**暗雷 1 / 3**）**隔离条数（按 reason 分组）** | 审计表新增行数，按 `JUDGE_REJECTED / POISON / RETRY_EXHAUSTED / CONFIRM_TIMEOUT` 分组 | **`CONFIRM_TIMEOUT` 非 0 = 有消息永远落不了库**，是比 DLQ 更早的告警信号 |
| ★（**铁律 4**）**Replay 补投条数** | `GraphitiReplayJob` 每轮实际补投的条数 | 长期 > 0 说明 scan 漏投或熔断在常态化冻结投递 |

### 5.5 版本化上线门限 + 样本量口径（P7c）

**定稿门限（写进配置，达标才许打开 `graphiti.recall.enabled`）**：

| 指标 | 门限 |
|---|---|
| 写入成功率 | **≥ 99%** |
| 抽取非空率 | **∈ [20%, 70%]** |
| 重复边率 | **< 2%** |
| DLQ 深度 | **= 0** |
| 人工判定 | 层 1 / 层 2 **无 P0 错误**（P0 = 关于用户的关键事实错误，如过敏、健康、身份） |
| ★ **MQ 积压之和**（补丁 7 + 暗雷 2） | **= 0**（熔断阈值只应在异常期被触及） |
| ★ **隔离条数 `CONFIRM_TIMEOUT`**（暗雷 1/3） | **= 0**（非 0 意味着有消息**永远**落不了库，必须先查清原因） |
| ★ **审计表 `JUDGE_REJECTED` 与 `POISON`**（暗雷 1） | 允许 > 0，但**必须能逐条解释**（前者是设计行为，后者是载荷 bug，出现即需修） |
| ★ 过滤后不足率（补丁 2） | **< 5%**（否则上调 `FETCH_FACTOR`） |

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
| **B1** | **写入链**：MQ 两级拓扑 + 三隐患修复 + `GraphitiScanConsumer`/`GraphitiWriteConsumer` + `MemorySignalJudge` + **高危词表快通道** + 重试/DLQ + 水位 + **DB CAS 幂等 + 失败回滚（补丁1）** + **双字段状态机 + `confirm_rounds`** + ★ **`graphiti_write_audit` 审计表（判否/毒消息/耗尽/超限 四类终态）** + **`GraphitiConfirmJob` 异步对账（窗口量纲修正，补丁3 + 暗雷3）** + ★ **`GraphitiReplayJob` 补投（铁律4）** + **429 显式拦截（补丁4）** + **优先级队列 + 重投时显式保优先级（补丁6）** + **`GraphitiBackpressureGuard` 背压熔断（信号换 MQ 积压，补丁7 + 暗雷2）** | **B0 + ★ 幂等实测（§3.5）** | 中（新增链路，但开关可关） |
| **B2** | **召回链**：`GraphitiMemoryRecaller` + 双通道并行 + 预算截断 + Prompt 铁律 + `{{episodicMemory}}` 槽位 + L1 窗口扩容与时间窗兜底 + **`conversation_archive` 集合 + 回忆类意图路由**（P1/P1-新） | B1 | 中（阻塞首字，需 shadow 验证） |
| **B3** | **管理面**：`SemanticMemoryController` 改读 Graphiti + 单条/按 episode 删除 + **记忆溯源（`fact.episodes[]` 反查原话）** + 前端「我的记忆」分层展示（记忆分类**拍扁**） | B2 | 低 |
| **B4** | **灰度验收**：三开关 + 影子模式 + **导出脚本与三张附表** + Micrometer 可观测性 + DLQ 监控 + **门限达标判定** + 回滚演练 | B3 | 低 |

**建议先只做 B0**：零业务风险、把所有契约陷阱一次性钉死；B0 完成后即可用真实 DTO 做端到端契约验证（对着 `192.168.88.130:8088` 打），再决定后面怎么走。

### 6.2 废弃清单（「不搞历史包袱」的物理动作）

**删除**：`HabitExtractor`、`HabitJudge`、`SemanticDeduplicator`、`SemanticMemoryService(Impl)`、`UserSemanticCandidate(Mapper)`、`ExtractedHabit(List)`、`HabitVerdict`、`SemanticRuleType`、`SemanticHabitVO`、`AssistantConfig` 里的 `habitExtractor()` / `habitJudge()` 两个 Bean、`application.yaml` 的 `semantic.*` 段、Qdrant `user_semantic_habits` 集合定义。

**★ 保留并改造** → **rev.2 改为：新建 `graphiti_write_audit`，并 DROP `user_semantic_memory`**（**推翻了 rev.1 的「复用」决议**，理由见 §7.3）：

审计表的语义在 rev.2 里从「判否日志」扩成了「**所有永不（重）投递的终态集合**」（暗雷 1 + 3.7.2），因此它需要四项**旧表都没有**的字段：`message_id`（外键到 `chat_message`，`NOT EXISTS` 子查询的必需项）、`reason`（枚举）、`content_snapshot`、`created_at`。
→ 复用 = 「改到面目全非 + 保留一个会误导后来者的表名」，**rev.1 复用它的唯一理由（零建表成本）已经不成立**。

⚠️ **语义切割不变**：它是**审计 / 隔离日志**，**不是记忆源**，不违反铁律 2。

**改造**：`SemanticScanConsumer` → `GraphitiScanConsumer`（拆出 `GraphitiWriteConsumer`）；`SemanticScanProducer`（保留冷却锁，消息体加水位字段）；`ChatStreamService.buildUserMemoryBlock` → Graphiti 召回 + **高危词表提交通道**；`MainChatAssistant` 加 `{{episodicMemory}}` 槽位；`CoreChatPrompt.txt` 加铁律 + 新块 + `{{context}}` 声明；`SemanticMemoryController` → Graphiti 数据源；`SemanticMemoryServiceImpl` 的类注释（「偏好 Vue3 vs 偏好 React = 0.8473，不存在可用阈值」）**保留作决策存档**。

**保留并复用**：`PromptSanitizer.sanitizeFact()`（消毒）、`ChatSummaryService`（L1 摘要）、`IntentRoutingService`（routing 骨架 + `EPISODIC_MEMORY` 分支扩语义）、`Bm25SparseEncoder`（稀疏检索）、`QdrantTemplate`（**稠密+稀疏 RRF 通用模板，`conversation_archive` 直接复用，零新代码**）、`QdrantConfig.initCollections()`（**配置驱动建集合，加 yaml 块即自动建好**）。

**数据清理**：
- Qdrant `user_semantic_habits` 集合 → **删除**
- MySQL `user_semantic_memory` 表 → ★ **rev.2：导出备查后 DROP**（不再复用。改新建 `graphiti_write_audit`，理由见 §7.3；字段需求已超出旧表结构）
- MySQL `graphiti_write_audit` → ★ **rev.2：新建**（四类终态的隔离/审计表；Confirm 与 Replay 的 `NOT EXISTS` 都依赖它）
- MySQL `user_semantic_candidate` 表 → **先停止写入，暂不 drop**（观察期后再定）
- MySQL `chat_message` → **加三列 + 一索引**（§3.8，含新增的 `graphiti_confirm_rounds`）

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
| 15 | `graphiti/GraphitiWriteAuditMapper.java` + `entity/GraphitiWriteAudit.java` | ★ rev.2：**独立新建 `graphiti_write_audit` 表**（不再复用旧表，理由见 §7.3）—— 四类终态：`JUDGE_REJECTED` / `POISON` / `RETRY_EXHAUSTED` / `CONFIRM_TIMEOUT`，同时是 P7 层 4 抽样的数据源 | B1 |
| 16 | `tools/GraphitiVerifyExport.java`（或独立脚本） | 导出三张附表（§5.6） | B4 |
| 17 | `service/GraphitiBackpressureGuard.java` | 背压熔断（补丁 7 + 暗雷 2）：**触发信号只认 MQ 物理积压**（`write` + 3 个 `retry` 的 ready 之和）；「未确认数」仅作观测/看板，**不得参与触发**；输出冻结/解冻信号 | B1 |
| 18 | `client/GraphitiErrorClassifier.java` | 异常分类（补丁 1/4）：`isRetryable` / `isToxic` / `isRateLimited`，**先看码再看 body 关键词** | B1 |
| 19 | `job/GraphitiReplayJob.java` | ★ rev.2：**补投责任人**（铁律 4）—— 扫「该投未投且未被隔离」的消息并投 `write.queue`；**必须显式保留 priority** | B1 |
| 20 | `mq/GraphitiQueueDepthProvider.java` | ★ rev.2：熔断信号的数据源 —— 封装 `RabbitAdmin.getQueueInfo()` 取 `write + 3 个 retry` 的 ready 之和，**catch 队列不存在** | B1 |

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
| 13 | ~~`pojo/entity/UserSemanticMemory.java` + `mapper/UserSemanticMemoryMapper.java`~~ | ★ rev.2：**删除**（不再复用；改为新建 `graphiti_write_audit`，见 §7.1 #15 与 §7.3） |

### 7.3 删除文件

`ai/aiservice/HabitExtractor.java`、`HabitJudge.java`、`SemanticDeduplicator.java`（如存在）、`service/SemanticMemoryService.java` + `impl/SemanticMemoryServiceImpl.java`、`mapper/UserSemanticCandidateMapper.java`、`pojo/entity/UserSemanticCandidate.java`、`pojo/*/ExtractedHabit.java`（含 List 版）、`HabitVerdict.java`、`enums/SemanticRuleType.java`、`pojo/vo/SemanticHabitVO.java`。

⚠️ **`UserSemanticMemory` 实体 + `UserSemanticMemoryMapper`：rev.2 改为删除**。**已定为「乙」（推翻 §0.3 的甲）**：

| 选项 | 做法 | 优点 | 缺点 |
|---|---|---|---|
| 甲（rev.1 采用，**已废弃**） | 复用 `user_semantic_memory` 表，改造字段语义 | 零建表成本 | ⚠️ **rev.2 起不再成立**：审计表需要 `message_id` / `reason` / `content_snapshot` / `created_at` 四项**旧表都没有**的字段，复用等于重写；且旧表名留着会误导后来者 |
| **乙（rev.2 定稿）** | 新建 `graphiti_write_audit`，导出备查后 DROP 旧表 | 语义干净、职责清晰；`message_id` 可建索引以支撑 `NOT EXISTS` 子查询 | 多一次建表 + 一次数据导出 |

**推翻理由（一句话）**：rev.1 选甲的唯一理由是「零建表成本」；而 rev.2 的审计表**语义已从「判否日志」扩成「永不（重）投递的终态集合」**（暗雷 1 + §3.7.2），字段需求变了 → **甲的成本优势消失，只剩缺点。**

### 7.4 数据面变更

| 面 | 变更 |
|---|---|
| **MySQL DDL** | ① `chat_message` 加 **4 列 + 2 索引**：`graphiti_dispatched_at` / `graphiti_synced_at` / ★ `graphiti_confirm_rounds` / ★ `graphiti_episode_uuid`（§3.8）；② ★ rev.2 **新建 `graphiti_write_audit`**（`id` / `user_id` / `message_id`(UNIQUE+索引，支撑 `NOT EXISTS`) / `reason` / `content_snapshot` / `detail` / `created_at`）；③ ★ rev.2 **导出备查后 DROP `user_semantic_memory`** |
| **RabbitMQ** | 新增：`graphiti.scan.delay.queue` / `graphiti.scan.dlx` / `graphiti.scan.queue` / **`graphiti.write.queue`（★ 声明 `x-max-priority: 10`，补丁 6）** / `graphiti.write.retry.{30s,2min,10min}.queue` / `graphiti.write.dlq`；删除：`semantic.scan.*` 系列 |
| **★ 运维步骤（补丁 6）** | ⚠️ **`x-max-priority` 是队列参数，若队列已存在且参数不同，`queue.declare` 会 406 PRECONDITION_FAILED。** 调整参数的顺序必须是：① 停消费者 → ② 确认 `graphiti.write.queue` 为空 → ③ `rabbitmqctl delete_queue` → ④ 启动应用重新声明 → ⑤ 恢复消费者。**首次部署队列不存在，无此问题；但任何后续调参都要走这一步。** |
| **Qdrant** | 新增集合 `conversation_archive`（配置驱动）；删除集合 `user_semantic_habits` |
| **Neo4j / Graphiti** | **无变更**（服务端已就绪） |

---

## 附录 A · 契约速查表（据容器源码实读，2026-10-12）

| 接口 | 方法 | 约束 | 响应 |
|---|---|---|---|
| `/messages` | POST | `{group_id, messages:[{content, role_type(user/assistant/system), role(**必填字段，可 null**), uuid(★★ **必须为 null**：非 null 时语义 = "update 一个已存在 episode"，传新 uuid 必抛 `NodeNotFoundError` 并静默丢弃), name(★ 我们用来承载确认锚点 `"cm_"+chatMessageId`), timestamp?, source_description?}]}`；**每条 message 独立成 episode**；**202 异步** | `{message, success}`（**不含 uuid**） |
| `/search` | POST | `{group_ids[]（**可选可 null，漏传=跨租户全局**）, query(必填), max_facts=10}` | `{facts:[FactResult]}`；**不过滤 invalid_at、无 group_id** |
| `/episodes/{group_id}` | GET | `last_n` 必填；**排序键 = `created_at`（落库时刻，已实测）**；**无 offset/游标**；未知参数静默忽略 | **11 字段**：`content, created_at, entity_edges, episode_metadata(恒 None), group_id, labels, name, source, source_description, uuid, valid_at`；时间格式 `...Z` |
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
| **19** | ★ Redis setnx(TTL 1h) + MQ 可重试 | **重试死锁**：重投回来的消息被自己的锁**永久拒收** | **删掉 Redis 层**，改 **DB CAS + 可重试失败回滚**（补丁 1） |
| **20** | ★ `max_facts` 按目标条数传 | 过滤作废后**召回为空**（有效事实排第 N+1 未被拉回） | **宽拉取 `limit × FETCH_FACTOR` → 过滤 → 截断**（补丁 2） |
| **21** | ★ 对账 `last_n` 硬编码 | 目标 uuid 被挤出窗口 → **无限重投** | 动态算窗口（补丁 3）；★ **但 rev.1 的公式 `max(50, X×2+10)` 本身也有洞 → 见 #28，定稿公式见 §3.6.1** |
| **22** | ★ 429 归入「4xx 毒消息」 | 高峰期新偏好**成批永久丢失** | **显式拦截 429/403**，走最长退避（补丁 4） |
| **23** | ★ 双通道合并后按 `validAt` 全局倒排 | **画像被近期情景挤掉** | **独立过滤、独立截断、物理分槽**（补丁 5） |
| **24** | ★ 队列已存在时改 `x-max-priority` | `queue.declare` **406 PRECONDITION_FAILED** | 先删队列重建（补丁 6） |
| **25** | ★ 用 DLQ 深度做背压信号 | 反应太慢（DLQ 涨 = 数据已丢） | 改用**主队列物理积压**（补丁 7）；⚠️ 补丁 7 原方案里的「+ 未确认数」这一半随后被**暗雷 2** 证伪 → 见 #27 |
| **26** | ★（**暗雷 1**）对账任务把 `dispatched_at IS NULL` **也纳入目标集** | `dispatched_at IS NULL` 有**三种**语义（未投递 / 判否 / 毒与耗尽）→ **判否的闲聊被后门强制重投进图谱**（前门拦、后门灌） | ① 对账只扫 `dispatched_at IS NOT NULL`；② 审计表 `NOT EXISTS`；③ 任务拆分为 Confirm + Replay（§3.6） |
| **27** | ★（**暗雷 2**）用 MySQL「未确认数」做熔断**触发** | 可重试失败会**回滚** `dispatched_at` → Graphiti 全挂时未确认数**趋 0** → **熔断器报「健康」、继续猛灌**（监控假死） | 触发信号**只认 MQ 物理积压**（`write` + 3 个 `retry` 的 ready 之和）；未确认数只作观测（§3.10） |
| **28** | ★（**暗雷 3**）`last_n` 按 `chat_message.created_at` 或「未确认数」计算 | 历史补投 / 长重试链 / 单条长期卡住 → 窗口**罩不住**目标 uuid → **无限重投** | 按 **`[最老目标的 dispatched_at, now]` 窗口内已投递条数**计算（§3.6.1） |
| **29** | ★（**补丁 1 自身引入**，rev.2 自查发现）毒消息「保留占位」但不进审计表 | 对账任务扫到它 → `/episodes` 里永远找不到 → 重投 → 又 400 → **无限互踢**（DLQ ↔ Confirm） | 毒消息 / 重试耗尽 / 对账超限**一律写审计表**（§3.7.2） |
| **30** | ★（**补丁 6 配套**）自己构造 `Message` 重投时未 `setPriority` | 高危消息**失败一次即降级为平民**，优先级队列形同虚设 | 所有自行 republish 的代码显式 `setPriority(原值)`；broker DLX 那一跳单独实测（附录 D #11） |
| **31** | ★★（**暗雷 4，实测**）给**新**消息传 `uuid` | `uuid != null` 的语义是「update 已存在 episode」→ `NodeNotFoundError` → **job 静默失败、什么都不写**，而 HTTP 已 **202** → **100% 丢数据且零告警** | **写入一律 `uuid = null`**（§3.5.3） |
| **32** | ★★（**暗雷 4 推论**）以为服务端会「按内容/uuid 去重」 | 服务端**没有任何原生去重能力**：uuid 不可预指定、内容不去重 → 重复投递 = 重复 episode + 重复 LLM 计费 | **CAS 是唯一防线**（铁律 1）；对账重投必须设宽限轮次（§3.6 ④） |
| **33** | ★（**暗雷 4 推论**）用 uuid 做「落库确认」的比对键 | 服务端 uuid **事前不可知**、事后无法反查 → 确认逻辑**根本写不出来** | 确认锚点改用 **`name = "cm_"+id`**（实测可写、可持久、可返回） |

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
| **补丁 1~7 的成立性** | 独立评审文档（2026-10-12）+ v3-定稿全文逐处复核（Redis setnx 见 §3.5/§3.3、`max_facts` 见 §2.3/§4.2、`last_n=K` 见 §3.6、4xx 分类见 §3.7、合并排序见 §4.2）—— **7 条全部成立，均已并入** |
| **暗雷 1~3 的成立性（第二轮评审）** | 第二轮评审文档（2026-10-12）+ rev.1 全文逐处复核：`dispatched_at IS NULL` 确在 §3.6 扫描条件中（已核实）、「未确认数」确在 §3.10 信号源中（已核实）、`last_n` 确用 `chat_message.created_at`（已核实）—— **3 条全部成立；但暗雷 3 的公式与暗雷 1 的修法已按 §0.6 修正** |
| **第四颗洞：毒消息 ↔ 对账任务互踢** | ★ **本版自查发现**（第二轮评审未提及）：rev.1 §3.7「毒消息保留占位」+ §3.6 对账扫描条件 **组合后必然互踢** → 已修（§3.7.2） |
| **`add_episode` 的 `uuid` 语义（暗雷 4）** | ① **源码**：`graphiti_core/graphiti.py:1154-1166`（`uuid is not None` → `EpisodicNode.get_by_uuid`）、`driver/neo4j/operations/episode_node_ops.py:140-156`（查不到即 `raise NodeNotFoundError`）；② **实机三组对照**：脚本 `_graphiti_idem_probe.py`（新 uuid + 不传 uuid）、`_graphiti_idem_probe2.py`（不传 / 已存在 uuid）、`_graphiti_probe_order.py`（排序键 + `name` 锚点）；③ **容器日志**：`Background job failed: NodeNotFoundError: node idem_{ts}_a not found`。**结论见 §3.5** |

## 附录 D · B1 阶段必须完成的实机验证项

| # | 验证项 | 方法 | 判据 |
|---|---|---|---|
| 1 | ✅ **已完成（2026-10-12 实测）** `add_episode` 对 **`uuid`** 的行为 | 新建消息分别用「**新 uuid** / **不传 uuid** / **已存在 uuid**」三种方式各投一次 | ★ **结论见 §3.5**：新 uuid → `NodeNotFoundError` **静默丢数据**；不传 → 正常落库；已存在 → update 且**不产生重复 episode** |
| 2 | ✅ **已完成（2026-10-12 实测）** `/episodes` 返回体字段名 | GET 一次看原始 JSON | **11 字段**，含 `uuid` / **`name`** / `source_description` / `created_at` / `valid_at`（§3.5.1）→ **`name` 可作确认锚点** |
| 3 | `source_description` **是否参与 LLM 抽取上下文** | 同内容、不同 `source_description` 两条 episode 对照 | 若参与 → 严禁放可被误抽的文本 |
| 4 | **乱序投递不改裁决** | 同一对话正序/倒序各投一次 | 最终图谱状态一致（§3.4 结论 4 的实证） |
| 5 | `DELETE /entity-edge/{uuid}` 在 v2 镜像可用 | 真实调用一次 | 遗忘权依赖它 |
| 6 | `RedisChatMemoryStore` 的**默认 TTL** | 写一条后观察过期 | 若很短 → 必须显式设置 ≥ 30min |
| 7 | ✅ **部分完成（2026-10-12 实测 3 条）** 单条 `POST /messages` 的**端到端落库耗时** | 投 20 条，记录 202→可用 的间隔 | 已测得 **15s / 20s / 27s**（远快于早前记录的 20~45s）。**正式校准仍需 20 条样本**；当前 3min 确认窗有充足余量 |
| 8 | ★（补丁 3）`/episodes?last_n=200` 的**响应时间与体积** | 真实调用一次，测耗时与 payload 大小 | 若可接受 → `last_n` 可用固定大值替代动态计算（更简单、不易算错） |
| 9 | ★（补丁 6）`x-max-priority` 的**插队是否真的生效** | 先压 10 条 `priority=1`，再投 1 条 `priority=10`，观察处理顺序 | 确认「高危排在队头」；空闲时优先级本就不生效（预期行为） |
| 10 | ★（补丁 4）Graphiti 对 **DashScope 429 的透传形态** | 人为触发限流，看返回是 429 / 403 / 被包装成 500 | 决定 `isRetryable()` 的判定逻辑（码 + body 关键词） |
| **2b** | ✅ **已完成（2026-10-12 实测）** `/episodes` 的**排序键** | 同 group 投两条：业务时间「今天」与「90 天前」，**后投**后者 | ★ **结论：排序键 = `created_at`（落库时刻），不是 `valid_at`** → §3.6.1 公式**成立**。**保留为「版本升级后的回归项」**（若上游改判据，公式会静默失效） |
| **11** | ★（**补丁 6 配套**）`retry.*` → `write.queue` 经 **broker TTL+DLX** 后 `priority` 是否保留 | 投一条 `priority=10` 并令其失败进 `retry.30s`，等 TTL 过期回到 `write.queue`，观察出队顺序 | 保留 → 无需额外处理；**丢失** → 必须改用手动 republish 并显式设优先级（§3.7.2） |
| **12** | ★（**暗雷 1 的正面回归**）`NOT EXISTS(审计表)` 是否真的挡住判否消息 | 造一条必被判否的闲聊 → 观察后续 10~20 分钟内它**是否被 Replay / Confirm 投出去** | 必须**始终不被投递**。若被投 → SQL 漏了 `NOT EXISTS`，图谱会被灌垃圾 |
| **13** | ★（**暗雷 2 的正面回归**）熔断信号在 Graphiti 真挂时**会不会涨** | 停掉 Graphiti 容器 / 断其出网 → 投 20 条 → 观察 MQ 积压之和 与「未确认数」两条曲线的差异 | 积压之和应**单调上涨并触发熔断**；未确认数应变 0（**这正是 rev.1 的盲区，必须亲眼看到它失效**） |

### D.1 实测结果（2026-10-13，B1 落地当日）

**载体**：`src/test/java/com/yangmf/mini_nodepad/GraphitiWriteChainDiagnosticTest.java`
（5 个有序用例，真 MySQL + 真 RabbitMQ + 真 Graphiti，约 1.5 分钟一轮）。
它同时是 **B4 的回归守卫**：只把「等 5 分钟 delay 队列过期」换成直接调用扫描消费者，其余全走生产路径。
配套两个探针：`_db_query.mjs`（只读 SQL）、`_b1_e2e.mjs`（夹具 / 状态取证 / 清理）。

| # | 验证项 | 结果 | 证据 |
|---|---|---|---|
| **7** | 单条 `POST /messages` 端到端落库耗时 | ✅ **≈12.3 s（上界）** | CAS 占位 → `/episodes/{group}` 能读到锚点，轮询粒度 10s。**比早前记录的 15~27s 更快**，3 分钟确认窗余量充足 |
| **9** | `x-max-priority` 插队是否生效 | ✅ 生效 | 出队顺序 `[10, 1, 1, 1, …]`（`_mq_priority_probe.mjs`） |
| **11** | `retry.*` → `write.queue` 经 broker TTL+DLX 后 priority 是否保留 | ✅ **保留** | 重试回来后 `properties.priority = 10` → **无需改成手动 republish** |
| **2b** | `/episodes` 排序键 = `created_at` 倒序 | ✅ 成立 | 用例 ⑤ 每轮断言（用 `OffsetDateTime` 比较，不用字符串比较） |
| **12** | `NOT EXISTS(审计表)` 真的挡住判否消息 | ✅ **正面回归通过** | 用例 ⑥：造一条满足全部补投条件的候选 → 控制组被捞到（1 条）→ 写 `JUDGE_REJECTED` 审计行 → 实验组 0 条；且**真实 mapper** 的 `selectReplayTargets` 同样挡住 |
| **6** | `RedisChatMemoryStore` 默认 TTL | ✅ 无风险 | 32 位 hex 键为 `ReJSON-RL` 类型、**TTL = −1（永不过期）** → 不存在会话中途记忆丢失，无需显式设置 |
| **8** | `/episodes?last_n=200` 的耗时与体积 | ⚠️ **不可测（数据不足）** | 全库 4 个真实用户 group 的 episode 数**均为 0**，探针 group 仅 1 条 → `last_n=20` 与 `200` 都是 23ms / 437 字节。**这两个数不能用来判断「固定大值是否可接受」** → 必须等出现 ≥200 条 episode 的真实 group 后重新校准 |
| 3 | `source_description` 是否参与 LLM 抽取上下文 | ⏳ 未做 | 需两条对照 episode + 等 LLM 抽取 |
| 4 | 乱序投递不改裁决 | ⏳ 未做 | 同上 |
| 5 | `DELETE /entity-edge/{uuid}` 在 v2 镜像是否可用 | ⏳ 未做 | — |
| 10 | DashScope 429 的透传形态 | ⏳ 未做 | 需人为触发限流 |
| **13** | 熔断信号在 Graphiti 真挂时是否上涨（暗雷 2 正面回归） | ⏳ 未做 | 需停容器 + 投 20 条。**这是 rev.1 盲区的唯一实证机会，不可省** |

### D.2 ⛔ 本轮实机验证抓到的两个 P0（静态审查不可能发现）

两条都是「Java 侧类型完全合法、只有真打一次库才暴露」的错误，**且失效方式都是静默的**。
它们也正是「附录 D 存在的理由」——**不要因为麻烦而跳过实机验证**。

#### P0-1 `LocalDateTime.MIN` 超出 MySQL DATETIME 定义域
（`GraphitiScanConsumer.resolveWindowStart`）

- 症状：`Incorrect DATETIME value: '+1000000000-01-01 00:00:00'`（**error 1525**）。
- 后果：**每个新会话的首次扫描必炸**（首次扫描没有 Redis 水位 → 必然走 `MIN` 分支）
  → 消息 reject 不 requeue → 经 DLX 进 DLQ → **发现层对全新会话完全不工作**，
  对外只表现为「记忆一直不生成」，日志里除了一条 DLQ 记录再无痕迹。
- 修法：改用 `EPOCH_FLOOR = 1970-01-01 00:00:00`（在定义域内，且远早于本工程任何真实数据）。
- 教训：**Java 的极值常量不是 SQL 的极值常量。** 凡把 `MIN` / `MAX` 之类的哨兵绑进 SQL，
  先查目标列类型（`DATETIME` 只有 1000-01-01 ~ 9999-12-31）的定义域。

#### P0-2 跨表字符列排序规则不一致
（`graphiti_write_audit` 建表漏写 `COLLATE`）

- 症状：`Illegal mix of collations (utf8mb4_0900_ai_ci,IMPLICIT) and (utf8mb4_unicode_ci,IMPLICIT)`（**error 1267**）。
- 根因：本库既有表（`book` / `chat_session` / `chat_message`）都是 `utf8mb4_unicode_ci`（RuoYi 建库时的老约定），
  而 **MySQL 8 的 `utf8mb4` 默认排序规则是 `utf8mb4_0900_ai_ci`**；DDL 只写了 `DEFAULT CHARSET utf8mb4`。
- 后果：`a.message_id = c.id` 这类**两个列直接比较**的子查询直接抛错 →
  **Confirm / Replay 两条对账 SQL 每轮必炸** → 消息永远确认不了 → 全部走到 `maxRounds`
  被误判成 `CONFIRM_TIMEOUT` 隔离。**整套写入链静默退化成「只投不认」。**
- 修法：建表显式 `COLLATE = utf8mb4_unicode_ci`；DDL 脚本内附**幂等补救**
  `ALTER TABLE graphiti_write_audit CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;`
- ⚠️ **为什么自查极难发现**：`existsByMessageId`（绑定变量 vs 列）**不会**报错 ——
  参数的可强制性（coercibility）为 4，列（2）胜出。**只有「列 vs 列」的子查询才暴露。**
  所以凡新增表，必须检查**每一条跨表列比较**，而不是检查「有没有用过那张表」。

### D.3 两个「自己的工具骗了自己」的教训（方法论）

1. **RabbitMQ `messages_ready` 是 5 秒快照统计**（`collect_statistics_interval` 默认 5000ms）。
   用它判断「队列刚被清空」会看到 `ready=1` 这种假残留，进而怀疑代码没清干净。
   → **量真实条数必须用 `POST /api/queues/%2F/{q}/get` + `ackmode=ack_requeue_true`。**
2. **`/episodes` 只有路径形式**：`GET /episodes/{group_id}?last_n=N`。
   写成查询参数 `/episodes?group_id=…` 返回 **404**，看起来像「这个用户没有数据」——
   我据此误判「10-12 的实测数据被清空了」。
   修正 URL 后实测：真实用户 group 存在但 episode 数为 0（`[]`），探针 group 有数据。
   → **一个 URL 形式写错，能让你得出与事实完全相反的结论。**

### D.4 一条测试卫生铁律（不遵守会得到「假绿」）

**图谱是有状态的，而 Graphiti 对重复投递零去重能力**（§3.5 铁律 1 的由来）。
夹具用固定 message id，**上一次运行遗留的 `cm_<id>` episode 会让本轮「等服务端落库」第一轮轮询就命中** ——
测试照样全绿，但落库耗时量出来的是「跑了两遍的间隔」这种毫无意义的数。

→ 因此 `fixture()` 的**第一步**必须是 `DELETE /group/{groupId}` 清空探针分区（见 `wipeProbeGraph()`）。
本轮就实测到过**两条同名 episode（created_at 相隔 6 分钟）**，正是没清导致的。
清理用 `_b1_e2e.mjs cleanup`（删图谱 group + MySQL 夹具行 + 审计行，并复核四类残留计数为 0）。

---

## 附录 E · 评审误判驳回记录（2 项 —— 不改架构）

独立评审文档另提出两条「漏洞」，**复核后不成立**，记录在案以免后续重复讨论：

### E.1 「纸糊的多租户隔离与安全漏洞」 → **驳回**

**评审主张**：要求 Graphiti 自身做 JWT 校验 / API 网关隔离 / 禁用 `/clear`。

**驳回理由**：

1. **Graphiti 是内网私有微服务**（`192.168.88.130:8088`，Docker 网络内），**不是公网 API**，唯一合法调用方是 Java 后端。要求一个内网底座自己做鉴权，是**典型的过度设计**。
2. **信任边界本来就在 Java 层** —— 这正是 **P5b** 拍的东西：`group_id` 由服务端从登录态推导、Client 层强制非空断言、对外接口不收 `groupId`。**防线已经在正确的位置上。**
3. **`/clear` 的封禁**：生产环境通过 Nginx 反代或 `iptables` 封禁该路由即可（见下方备注），**无需改服务端**。

⚠️ **但评审的这条追问引出一个真实待办（不属于本 Phase）**：**Docker Engine API 2375 端口无 TLS 无鉴权**（等价 root 权限）—— 这才是内网真正的敞开面。已在高危遗留清单中，优先级最高，**但不属 Phase 4 范围**。

> 📌 **备注**：`/clear`（无参数 = 整图清空）的封禁建议写进部署文档：在 Nginx 层对 `POST /clear` 直接返回 403；或对 `8088` 端口做来源白名单（仅允许 Java 后端所在主机）。

### E.2 「层 4 抽样无法发现漏拦，应改为全量输入随机抽样」 → **驳回**

**评审主张**：层 4（随机 30 条被 `MemorySignalJudge` 判否的原文）是「逻辑绕口令」，应当改为「对全量输入随机抽样」。

**驳回理由**：

1. **被判定器拦下的消息，本身就已被 AI 判为「不需要记忆的废话」** —— 抽查这些「废话」里有没有混进真偏好，**这正是在查「漏拦」**（该进没进）。逻辑是通的，不是绕口令。
2. **「全量输入随机抽样」效率极低**：绝大多数样本会是「帮我写代码」「你好」这类纯废话，抽 30 条可能 28 条无信息量。**针对性抽样（只抽"疑似漏掉的"）才是最优解**，这也是 P7a 拍定的四层协议。
3. **成本对比**：层 4 只需一个 `WHERE judge_result = 'REJECTED'` 的查询；全量随机抽样需要先把**所有**输入落表（含大量无价值噪音），**成本更高、信噪比更低**。

> 📌 **但评审这条追问有一个有价值的副产品**：它迫使我们在 P7a 里把「判否的原文必须落审计表」写成了**硬约束**—— 这是层 4 能存在的前提，也是层 4 与「全量抽样」的真实差别所在。

