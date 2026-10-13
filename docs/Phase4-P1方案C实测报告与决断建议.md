# Phase 4 · P1 方案 C 实测报告与决断建议

> 实测时间：2026-10-12 20:40 左右
> 实测对象：`192.168.88.130:8088`（custom-graphiti:v2）+ 同机 Neo4j `192.168.88.130:7474`
> 方法：进容器实读源码（`graphiti_core` 0.30.x）+ 只读 Cypher 直查 Neo4j + HTTP 实测
> **本文只回答一个问题：方案 C（用 Graphiti 的 episode 能力做「语义化原话回溯」）能不能成立。**

---

## 一、一句话结论

> **C 不成立，且失败原因恰恰是你想撤回的那件事：Graphiti 的 episode 层就是 BM25，它没有任何向量能力。**
> 想用 Graphiti 得到"语义级原话召回"，是在一个**只装了 BM25 的房间里找向量**。
>
> **建议选 B**——但**不是因为"BM25 差"**（这个理由我可以证伪），而是因为**你自己的架构铁律本来就要求 B**（见 §五）。
> 而且 B 的真实成本被严重高估：**实测约「1 个配置项 + 30~50 行代码」**（见 §四）。

---

## 二、C 的三种可能解读，逐一判定

### C1「用 `GET /episodes/{group_id}?last_n=N` 做检索」→ ❌ 否定

**事实**：该接口就是"按时间倒序取最近 N 条"，没有任何检索能力。

**实测（决定性）**：给同一个接口加一个完全不存在的检索参数，返回结果与不加时**逐字节一致**：

| 请求 | HTTP | 返回 |
|---|---|---|
| `GET /episodes/test_user_01?last_n=2` | 200 | 2 条（React 那条 + Vue3 那条） |
| `GET /episodes/test_user_01?last_n=2&query=React` | 200 | **完全相同**——`query` 被 FastAPI 静默忽略 |

**源码佐证**（`/app/graph_service/routers/retrieve.py`）：

```python
@router.get('/episodes/{group_id}')
async def get_episodes(group_id: str, last_n: int, graphiti: ZepGraphitiDep):
    episodes = await graphiti.retrieve_episodes(
        group_ids=[group_id], last_n=last_n, reference_time=datetime.now(timezone.utc))
    return episodes          # ← 签名里只有 (group_id, last_n)，没有 query
```

**附带硬伤**：`last_n` 是"最近 N 条"，**没有 offset / 游标**。用户有 1000 条对话时，想翻出三个月前那一条，只能把 `last_n` 设到几百——性能和传输都不可接受。

### C2「用底层 episode_search 做语义检索」→ ❌ 三重否定

**证据 1：枚举里根本没有向量选项**（`graphiti_core/search/search_config.py`）

```python
class EpisodeSearchMethod(Enum):
    bm25 = 'bm25'                      # ← 只有这一个值

class CommunitySearchMethod(Enum):     # 对照组
    cosine_similarity = 'cosine_similarity'
    bm25 = 'bm25'
```

**证据 2：Graphiti 官方最佳实践配方里，episode 也只给 BM25**（`search_config_recipes.py`）

| 检索层 | `COMBINED_HYBRID_SEARCH_RRF` 的 search_methods |
|---|---|
| **edge（事实）** | `[bm25, cosine_similarity]` ✅ 有向量 |
| **node（实体）** | `[bm25, cosine_similarity]` ✅ 有向量 |
| **episode（原文）** | **`[bm25]`** ❌ **无向量** |
| community（社区） | `[bm25, cosine_similarity]` ✅ 有向量 |

**证据 3：物理上不可能有**——三条独立佐证：
- `EpisodicNode` 字段只有 `source / source_description / content / valid_at / entity_edges / episode_metadata`，**没有 `embedding` 属性**（`nodes.py` 的 `save()` 参数同样没有）；
- 直接查 Neo4j 全部索引：**35 个索引里没有任何 `VECTOR` 类型**（只有 FULLTEXT + RANGE + LOOKUP）；
- `episode_search()` 的函数签名里虽然有 `_query_vector` 参数，但**函数体内从未被使用**（`_` 前缀也说明是历史遗留）。

**证据 4：服务端压根没暴露**

```python
# /app/graph_service/routers/retrieve.py
relevant_edges = await graphiti.search(...)       # Graphiti.search() 返回 list[EntityEdge]
facts = [get_fact_result_from_edge(e) for e in relevant_edges]
return SearchResults(facts=facts)                 # ← episodes 在这里被丢弃
```

而 `Graphiti.search()` 的返回类型签名就是 `-> list[EntityEdge]`（docstring 明确写着 *"num_episodes set to 0"*）。真正的全量检索方法是 `graphiti.search_()`，**服务端没有任何路由调用它**。

### C3「搜事实 → 用 `fact.episodes[]` 反查原文」→ 🟡 **部分可行，但只解决另一个问题**

这是 C 唯一站得住的部分，且**实测证明它工作**：

```
POST /search  →  facts[0].episodes = ["3850349c-554e-4b1e-8417-40f11f29d2fe"]
                  ↓ 反查
GET /episodes/test_user_01?last_n=N
                  ↓
{"uuid":"3850349c-...","content":"user(user): React 太麻烦了，我改主意了，以后我的前端强制使用 Vue3，不要再提 React。","valid_at":"2026-10-12T09:51:56.345970Z", ...}
```

**它能让"从一条记忆回溯它的原话出处"**（= 记忆溯源 / 点开看详情 / 展示证据），这是个白送的能力，值得做。

**但它不能做"语义检索某段旧聊天"**，两个硬伤：
1. **只能反查到"被抽成事实的那些对话"**——而"我昨天原话怎么怼那个产品的"这类内容，**恰恰最容易没被 qwen-plus 抽成实体/关系**（纯情绪、无实体、无关系）。你要找的正是没进图谱的那些。
2. `last_n` 无游标（同 C1）。

---

## 三、我必须诚实纠正我自己：BM25 中文**能命中**

我原本的预判是"Neo4j 的 standard analyzer 对中文会零命中"。**实测把这个预判打掉了**——我直接对 Neo4j 跑 `db.index.fulltext.queryNodes('episode_content', $q)`：

| 中文 query | 结果 |
|---|---|
| `我平时写代码有什么习惯` | ✅ 命中，score **1.336** |
| `前端` | ✅ 命中 |
| `习惯` | ✅ 命中 |
| `我改主意了` | ✅ 命中，score **1.797** |
| `以后我的前端强制使用` | ✅ 命中，score **1.703** |
| `React` / `Spring`（对照组） | ✅ 命中 |

索引 analyzer 实测为 `standard-no-stop-words`，Lucene 的 Unicode 分词对 CJK 会切出可检索的 token，所以**中文不是"搜不到"，是"搜得太宽"**。

**但这里暴露出一个比"能不能命中"更值钱的问题**：**区分度极差**。

- query `我平时写代码有什么习惯` 时，**不相关的那条 episode 也被召回**了（score 0.045 vs 正确的 1.336）——中文按字切分，`的 / 了 / 一` 这类高频字让任意中文文本都能蹭上分；
- query `React` 时，**正确命中的分数只有 0.045**，和噪音分 0.035 几乎不可分。

**推论**：在非中文语料上，分数分布是长尾的；而 `reranker_min_score` / score threshold 需要一个能"砍掉噪音、留住真命中"的阈值——**在这套打分下不存在这样的阈值**。阈值定高 → 真命中被砍；定低 → 全是噪音。**这才是 BM25 在这里"不可用"的真实机理。**

---

## 四、推翻你的第二个前提：B 的成本被严重高估

你说"**宁愿承受新建一条链路的代价**"——我实测了你项目里的代码，**代价根本不是一条链路**：

### 1. 建集合是**配置驱动**的，一行配置

`QdrantConfig.initCollections()`（`@PostConstruct`）会遍历 `spring.data.qdrant.collections`，**逐个 checkAndCreate + 建 payload index**：

```java
for (QdrantProperties.CollectionConfig config : properties.getCollections()) {
    checkAndCreateCollection(client, baseUrl + config.getName(), config.getName(), config.getDimension());
    for (QdrantProperties.IndexConfig index : config.getIndexes()) {
        createPayloadIndex(client, baseUrl + config.getName(), index.getName(), index.getType());
    }
}
```

现有两个集合（`application.yaml`）：

| 集合 | 维度 | 用途 | 处置 |
|---|---|---|---|
| `dynamic_brain` | 1024 | 上传文档切片 | 保留 |
| `user_semantic_habits` | 1024 | Phase 3 习惯查重索引 | **本次删除** |

→ **新增对话档案集合 = 在 yaml 的 collections 里加一个 `{name, dimension: 1024, indexes}` 块**，启动即自动创建，零 Java 代码。

### 2. 写入与检索**都是现成的通用方法**，直接复用

`QdrantTemplate` 已经是成熟的**稠密 + 稀疏双向量**模板：

| 方法 | 能力 | 对话档案能否直接用 |
|---|---|---|
| `upsert(collection, pointId, textToEmbed, rawText, payloads)` | 同时写稠密向量 + BM25 稀疏向量 | ✅ 直接用 |
| `search(collection, queryText, exactFilters, limit)` | **稠密+稀疏 RRF 融合检索** | ✅ 直接用 |
| `searchByDenseSimilarity(...)` | 纯稠密余弦（score 即真实相似度，可设阈值） | ✅ 直接用 |
| `delete` / `deleteByPageId` / `fetchChunkTextsByIndexes` | 删除 / 直取原文 | ✅ 直接用 |

**真实增量 = 1 个配置项 + 一个 Service 方法（组装 payload、切块、调用 upsert）+ MQ 加一个分支**，约 30~50 行。**不需要写任何新的 Qdrant 代码。**

### 3. 一个技术细节提醒（会影响设计）

`Bm25SparseEncoder` 是**项目自研的本地稀疏编码器**（不是 Lucene）。中文分词质量未知，RRF 融合时稀疏通道若噪音大，会拉低整体质量。

→ **建议对话档案集合只走稠密通道**（`searchByDenseSimilarity`），因为"找原话"靠的是**语义相似**，不是关键词匹配。这正是你想要的"提高召回智商"。

---

## 五、支持 B 的最强论证：你自己的架构铁律本来就要求 B

回看你定的边界原文：

> "Qdrant 退守为纯粹的'客观历史切片'和'外部 RAG 知识库'。**它只负责存储发生了就永远不会改变的文本片段，不做任何逻辑裁决。**"

把这句话套到"对话原话"上：

| 对象 | 会随时间改变吗 | 需要裁决吗 | 归属 |
|---|---|---|---|
| "我以后只用 Go"（**偏好**） | ✅ 会（下次可能改口） | ✅ 需要 `invalid_at` | **Graphiti** |
| "我昨天说过'Java 太啰嗦'这句话"（**原话记录**） | ❌ 永不改变 | ❌ 不需要 | **Qdrant** |

**对话原话是"发生了就永远不会改变的文本片段"的教科书定义**——昨天那句话不会因为今天改口而改变。所以**把对话原话放进 Qdrant，正是你这条铁律的靶心，不是违例**。

真正违例的是"把**偏好**同步到 Qdrant"——那是 Phase 3 的 `user_semantic_habits`，**本次删除**。

**并且**：你担心的"风险一①（Qdrant 与 Graphiti 打架）"，根源**不是"原话进了 Qdrant"**，而是**"从 Qdrant 捞出的原话被当成了当前事实"**。
- 这靠**类别声明**解决（P2：`{{context}}` / `{{historyArchive}}` 块首明写"这是历史记录/资料，不代表用户当前事实"），
- 而不是靠"不存原话"。

**顺带纠正一个更危险的混淆**：真正最容易让模型精神分裂的，是 `dynamic_brain` 里的**用户上传资料**——那是**用户从来没说过的话**。`conversation_archive` 里好歹是用户自己说的。所以 P2 的类别声明对 `dynamic_brain` 是**必需**，对 `conversation_archive` 反而**次要**（标个"用户历史原话 + 时间"即可）。

---

## 六、修正后的建议

### 选 B（新建对话档案集合），但定位要写死

| 维度 | 建议 |
|---|---|
| 集合名 | `conversation_archive`（"档案"，语义上就带"历史、只读、不改"的暗示） |
| 维度 | 1024（与现有 `EmbeddingModel` 输出一致，必须实测确认） |
| 索引 | `userId`（keyword，按人过滤）、`sessionId`（keyword）、`createdAt`（integer，时间倒序） |
| 检索通道 | **只走稠密**（`searchByDenseSimilarity`），不用稀疏通道 |
| 写入内容 | 对话轮次切片 + `rawText` + 时间戳（**与写入 Graphiti 共用同一批 MQ 消息**，零额外采集成本） |
| 定位声明 | 进 Prompt 的块首必须带：`[用户历史原话 · {日期}]` + "这是历史记录，不代表用户当前状态" |
| 与图谱关系 | **只读并行**，不参与事实裁决；与图谱冲突时**图谱优先**（沿用你定的铁律） |

### 白捡的 C3 能力，建议一并做（零额外成本）

`fact.episodes[]` → `/episodes` 的**记忆溯源**链路（"这条偏好是从哪句话提炼的"），用于"我的记忆"页面的**证据展示**。这是你现在已经有的接口，**不需要新建任何东西**。

### 你原来的判断，我按证据拆成两半

| 你的判断 | 我的核对结论 |
|---|---|
| "撤回 A" | **同意，但理由不同**。A 的问题不是"BM25 差"，而是 A 是 MySQL 关键词检索、**没有稠密向量**。而项目里**已经有稠密能力**（`QdrantTemplate`），只是没用在这件事上。 |
| "BM25 命中率极差，存在严重语义鸿沟" | **对一半**。"能不能命中"——中文 6/6 命中，不差；"**打分能不能区分**"——实测分数 0.045 vs 0.035 几乎不可分，**确实不能用**。机理是"中文按字切分导致打分退化"，不是"BM25 这个算法不行"。 |
| "倾向 B 或 C" | **C 判死**（§二），**B 正确**，但成本估算要下调一个数量级（§四）。 |
| "Qdrant 死守底线" | **完全同意，且这条铁律正好支持 B**（§五）。 |

---

## 七、需要你拍板的新问题（P1-新）

原 P1 是"要不要给对话原话建向量索引"，答案已明确为**要**（B）。但它拆出了一个新的、更重要的决策：

> **P1-新：`conversation_archive` 的召回是否要参与"事实性回答"？**

- **选项 ① 只做"引用/回忆"**（推荐）：只有当用户问"我昨天/之前说过什么"这类**回想型**问题时才召回，回答里**必须带出处与时间**；常规提问（"我该用什么框架"）不召回它。风险最低。
- **选项 ② 常规提问也召回**：把它当成第三路上下文，靠 Prompt 权重压制。**风险更高**——它必然引入"历史原话 vs 当前事实"的冲突（你担心的风险一①的真实形态）。

我倾向 **①**，理由：**它是"找原话"的库，不是"找真理"的库**；混合召回等于把风险一①的风险敞口主动扩大，而收益（多一点点上下文）不成比例。

---

## 附：本次实测的证据清单（可复现）

| # | 断言 | 证据来源 |
|---|---|---|
| 1 | `/episodes` 无检索能力，未知 query 参数被静默忽略 | HTTP 实测：`?query=React` 与不带参数返回逐字节一致 |
| 2 | `/episodes` 返回完整原文（`content`）+ `valid_at` + `group_id` + `entity_edges` | HTTP 实测响应体 |
| 3 | 时间格式为 `...Z`（**注意与 `/search` 的 `+00:00` 不同**） | `/episodes` 返回 `"created_at":"2026-10-12T09:51:39.672355Z"` |
| 4 | `EpisodeSearchMethod` 枚举只有 `bm25` | 实读 `graphiti_core/search/search_config.py:44` |
| 5 | 官方配方中 episode 只有 `[bm25]`，其余三层都有 `cosine_similarity` | 实读 `search_config_recipes.py` 三个配方 |
| 6 | `EpisodicNode` 无 embedding 属性 | 实读 `graphiti_core/nodes.py:318` 与 `save()` 参数 |
| 7 | Neo4j 35 个索引中无任何 VECTOR 索引 | Cypher `SHOW INDEXES` |
| 8 | `episode_search()` 的 `_query_vector` 参数从未被使用 | 实读 `search.py:664-761` |
| 9 | `/search` 路由丢弃 episodes，只返回 edges | 实读 `graph_service/routers/retrieve.py` |
| 10 | `Graphiti.search()` 返回 `list[EntityEdge]`（"num_episodes set to 0"） | 实读 `graphiti_core/graphiti.py:1586` |
| 11 | BM25 中文能命中（6/6），但打分区分度极差 | Cypher `db.index.fulltext.queryNodes('episode_content', $q)` 实测 |
| 12 | analyzer = `standard-no-stop-words` | Cypher `SHOW FULLTEXT INDEXES` 的 `options` |
| 13 | `fact.episodes[]` 可为记忆溯源提供原话 | `POST /search` 实测响应 |
| 14 | Qdrant 建集合是配置驱动，`QdrantTemplate` 已有双路 RRF | 实读 `QdrantConfig.java`、`QdrantTemplate.java`、`application.yaml` |
