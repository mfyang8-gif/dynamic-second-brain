# 项目长期记忆

> **两个工程**：后端 `Mini_Nodepad`（RuoYi 风格 Spring Boot 3.4 + Java 21）；前端 `mini-notebooklm-web`
> （Vue 3.5 + TS 5.9 + Vite 8 + Pinia 4 + reka-ui + Tailwind v4）。前端辅助脚本 `_*.py` 只在**前端目录**。
> ⚠️ **会话 CWD 不一定在本工程**（曾落在 `D:\7-Zip\springai_tes`）→ 动手前先确认。

## 一、环境陷阱（本机特有，跨项目通用）

- **Bash 工具 PATH 坏**：`ls`/`find`/`head` 全 command not found，复合 `cd` 不生效 → 用 Glob/Read，
  或「绝对路径 + Python/Node」。PowerShell **不回显 stdout**，要输出用 Python 打印。
- **代理**：Bash 自带 `http_proxy`（端口随会话变）—— python urllib 走代理、Node fetch 不走
  → 同一地址两者结论可能相反。Python 加 `ProxyHandler({})` 或 `env -u http_proxy`。
- **npm 必须绕代理**：全局 `~/.npmrc` 配了失效代理端口 → Python subprocess 调 npm，清
  `HTTP(S)_PROXY`/`ALL_PROXY`（含小写）+ `--proxy '' --https-proxy ''` + `--registry https://registry.npmmirror.com`。
- **Java/Maven**：`JAVA_HOME=D:\java`；Maven 3.9.4 在 `/d/maven/apache-maven-3.9.4-bin/apache-maven-3.9.4/bin`。
  离线编译 `mvn -o -DskipTests compile`。跑单个测试要**完整生命周期**
  （`mvn -o -Dtest=X -Dsurefire.failIfNoSpecifiedTests=false test`），直接 `surefire:test` 不编译测试类。
- ⚠️ **`env -u XXX ... mvn ... > log` 在前台会把输出整段吞掉（log 为 0 字节而 rc=0）**
  —— 2026-10-13 实测，同一个命令在 `run_in_background: true` 下却正常。
  → 前台跑 Maven 且要落日志时，**改用 `export JAVA_HOME=... ; export PATH=... ; unset http_proxy ...`
  再直接调 `mvn`**（这个形式实测正常）。看到「rc=0 但日志 0 字节」先怀疑这个，别怀疑 Maven。
- ⚠️ **同一文件多处 Edit 并行下发会互相覆盖**（只最后一个生效，但都报 success）→ 必须**串行**改；
  改完回读产物核对（typecheck/build 覆盖不到 CSS 变量值）。
- **写注释规矩**：CSS 注释里**不要有 `*/`**；HTML 注释里**不要有连续两个短横线**（都真踩过）。
- ⚠️ **工具侧长驻进程不可靠**：前端 dev server 起不来（`&`/后台/DETACHED 全被回收），
  `nohup mvn spring-boot:run` 也会卡死。→ 需要常驻服务时：要么让用户自己起，要么改成
  **可在 `mvn test` 里前台跑完的集成测试**（后者额外好处：成为回归守卫）。
- **`taskkill /F /PID` 在 MSYS 下报 `无效参数 'F:/'`** → 加前缀 `MSYS_NO_PATHCONV=1`。
  杀进程前先 `tasklist` 对内存：Spring Boot ~500MB、IDE 的 java 偏小（**别误杀 IDEA**）。
- ⚠️ **`git push` 可能长时间挂住（>2 分钟无输出）** —— 2026-10-13 实测两次。
  两个因素叠加，都用上就稳：
  1. **凭据助手**：`credential.helper=helper-selector`（→ Windows 的 `manager`），
     可能弹 GUI 或等待输入 → 加 **`GIT_TERMINAL_PROMPT=0`** 强制走 Credential Manager 缓存。
  2. **全局 git 配了代理且 https 指向已知坏端口**：`http.proxy=127.0.0.1:10808`、
     `https.proxy=127.0.0.1:33210`（**33210 就是 npm 那个坏端口**）
     → 加 `-c http.proxy= -c https.proxy=` 绕过。
  可用形式：`GIT_TERMINAL_PROMPT=0 git -C <repo> -c http.proxy= -c https.proxy= push origin <branch>`。
  诊断顺序：先 `git ls-remote`（若 1 秒内成功 → 网络没问题，问题在凭据）。

### 8082 / dev server
- 后端 dev 端口 **8082**（`src/main/resources/application-dev.yaml`，默认 profile=dev；前端 vite proxy 指它）。
  后缀是 **`.yaml` 不是 `.yml`**。
- 前端 dev server **必须由用户自己起**（工具侧起的不活过回合结束）；用户报「用不了」先按此前提排查。
- 工具侧起过 8082 后**收尾必须 `taskkill`**，否则用户回 IDEA 报 Port 8082 in use。

## 二、前端 CSS 铁律

- Tailwind v4 **CSS-first，无 `tailwind.config.js`**；主题在 `src/styles/index.css` 的 `@theme inline`。
- 三层 CSS：`primitives.css`（`--x-*` 原语，三风格唯一实现处）→ `tokens.css`（语义层，变量名冻结）
  → `index.css`（`@theme inline` + 组件类）。组件只消费语义类，换风格只改原语层。
- 三维正交：`data-theme`(light/dark) × `data-style`(apple/swiss/editorial) × `data-accent`(7 色，仅 apple)。
- 必守：
  1. **`font-serif` 默认 ui-serif/Georgia** → 必须在 `@theme inline` 写 `--font-serif: var(--x-stack-serif)`。
  2. **`@theme inline` 只认 `--color-*` 键名**。`text-fg-invert` 是**不存在的类**（不生成 CSS、静默失效，
     曾致导图根节点纯黑无字）→ 要用就补同值别名。
  3. **压缩器把同一选择器拆成多块** → 核对产物要**拼接**（setdefault + +=），dict 赋值刷一片假 MISS。
     等价写法：`#ff0000`→`red`、`#ffffff`→`#fff`、`150ms`→`.15s`、选择器引号被去掉。
  4. **别用 `border-bottom: var(--x, none)` 当简写开关**（变量为 none 会杀掉整条边、误杀其余风格）→
     完整 border 担宽度/样式，可选边只用 `border-bottom-color` 覆色。
  5. **`:root` 的 `--x-*` 会沿继承链渗入 `[data-style]` 段**（fallback 只在整链未定义时生效，
     曾致 Editorial 叶节点凭空长灰底）→ 每个 `[data-style]` 段**显式写全**自己需要的变量。
  6. **Tailwind v4 分层**：`@layer components` 会被 `@layer utilities` 覆盖（同优先级后者胜）→
     「必须压过工具类」的规则写在文件末尾、**不带 layer**（unlayered 优先级最高）。

## 三、关键区块（设计文档第 10/15/16 章）

- **导图沉浸态（16 章）**：放大态画布**零图形库**（D3/markmap 会把节点画成 SVG `<text>`、无法复用原语）
  → 纯 Vue + CSS `transform`；节点是真 `<button>`、连线 SVG `<path>` 三次贝塞尔 +
  `vector-effect:non-scaling-stroke`（线宽不随缩放变粗）。布局纯函数 `utils/mindmap-layout.ts`。
  叶节点 = 提问入口 → 右栏切 `AiCopilot`（`stores/layout.ts` 的 `rightPanelView`；四道闸 `composables/useAskGate.ts`）。
  模板**零 `data-style` 判断**，差异全在 `--x-mm-*` 原语。缩小态（右栏抽屉）= VS Code 级竖排缩进树。
- **MD3 悬浮岛（apple）**：画布 `#F0F4F9`/暗 `#131314` + 三白岛（暗 `#1E1F20`）24px 圆角；右栏
  `grid-cols-2` 胶囊工具箱 + tonal 五色；左栏图标绝对中性灰、彩色只给右栏。图标 `unplugin-icons` +
  `@iconify-json/material-symbols`（Rounded），尺寸靠 CSS `font-size` 下发。
  ⚠️ 风格几何变量（`--x-island-pad/gap`）必须放 `[data-style]` **基础段**，明暗段只放颜色
  （写进 light 段会让暗色下 padding 归零、三岛贴死，只有量测能逮到）。
- **面板开合（15 章）**：左栏开合入口**只有**中栏顶栏 `PanelToggle`（栏头 `←` 与左边缘把手已删，
  **别加回来**）；右侧抽屉**必须贴死视口右缘**（"轨道常驻 + 抽屉让位"已被用户否掉并回滚）；
  Swiss 开关闲置态 = **实心墨块**（不要汉堡三线）；`<aside>` 不能加 `overflow-hidden`（轨道 hover 长不出去）；
  三套开关形态全靠 `[data-style]` 在 CSS 切换，模板零风格判断。
- **浮层 vs 面板（10 章）**：Editorial 的 `--x-card`/`--x-panel` 透明是**面板**纸张感；
  **浮层必须实心**（否则与底下叠字）→ 所有浮层挂 **`.overlay-surface`**（AppDialog 挂一处覆盖全部 Dialog），
  Editorial 覆写段写在 index.css **顶层**（脱 @layer 才盖得过 utilities）；变量 `--x-overlay-paper/line/edge/grain`。
  `--x-card`/`--x-panel` 透明有反向断言锁死，别顺手改实。
- **改风格或动三栏骨架前先读** `mini-notebooklm-web/docs/多主题架构设计-风格与主题色.md`（13 字体/14 过渡/15 面板/16 导图）。

## 四、⛔ 路由过渡白屏（绝不能改回去）

`src/App.vue` 路由出口的 `<Transition name="route">`：
- **永远不能加 `mode="out-in"`**：Vue 3.5 + vue-router 5 下会拿到 `undefined` 插槽值 → 渲染成注释占位符
  **永久停住** → 点「书库」整页纯白、**控制台零报错**、刷新才好（加 `:key` 无效）。不能带 out-in
  → 过渡期新旧两页同时在场 → `.route-leave-active` 用 `position: absolute` 让离开态脱离文档流。
- **配套铁律**：`views/*` 模板根元素在组件存活期**绝不允许**被内部 v-if/v-else 换掉
  （Transition 离开时只认【第一次挂载】的根 el）→ 需要二态就套一层 `<div class="route-shell">` 稳定外壳。
- 取证：`_cdp_route_repro.mjs`。**白屏 + 控制台干净 = 去看 DOM 有没有元素**，别猜 JS 报错。

## 五、CDP 探针（真 Chrome，零依赖）

本机无 `agent-browser` CLI，但 Chrome 在 `C:\Program Files\Google\Chrome\Application\chrome.exe`，
Node 22 自带 `WebSocket`。测试账号 `demo / demo12345`，token 存 localStorage 的 `mnl.token`；
登录走 `/api/v1/auth/login`，**authorization 头是裸 JWT、不带 Bearer**。探针产物落 CWD。

- `_cdp_probe.mjs` 通用 7 步导航 + DOM 体检 + 截图（设 `MNL_TOKEN`/`MNL_BOOK`/`MNL_OUT`）。
- `_cdp_theme_shot.mjs` 三风格「创作入口/来源行/添加来源」逐槽位量测。**改三栏视觉后必跑**。
- `_cdp_mindmap.mjs` 导图专用（`CANVAS_PROBE`）。`_cdp_style_shot.mjs` 验 **hover 才出现**的样式
  （必须 CDP `Input.dispatchMouseEvent` 移**真指针**，改 class 触发不了 `:hover`）。
- `_cdp_md3_shot.mjs` MD3 悬浮岛专用（apple 明暗全量 + Swiss/Editorial 隔离守卫）。**动 apple 段后跑**。
- `_png_lines.py` 最小 PNG 解码器扫长直线，把用户说的"那条线"定位到像素坐标与颜色（**先量再问，别猜**）。
- ⚠️ `document.body.innerText` 会被 DOM 注释污染；页面会加载历史消息，断言收敛到**最后一条 `.cm-ans`**。

## 六、文档组织约定（2026-10-11 定）

- 两个 docs（后端 `Mini_Nodepad/docs`、前端 `mini-notebooklm-web/docs`）各自只留**现行**文档，
  各有一份 `文档索引.md` 登记「待办 / 现行 / 归档」三张表；已完成/失效移到同目录 `归档/`。
- 新增文档顺手在 `文档索引.md` 登记一行。
- `Mini_Nodepad/docs/后端待办清单.md` 三项（引用原文 / 重新生成 / 赞踩）**已全部实现**
  （随 `c929737`）→ **2026-10-13 已销项、文件移入 `归档/`**。实际路径与原建议签名不同：
  引用原文 = `GET /api/v1/knowledge/pages/{pageId}/chunks/{chunkIndex}/context`；
  重新生成 = `POST /api/v1/sessions/{sid}/messages/{mid}/regenerate`（两步换 `instructionToken`）。
- 2026-10-13 又归档 4 份被取代设计稿（Phase4 草案/v2/v3-定稿、双路并发重构方案-待审核）
  → 现行 docs 收敛为 7 份内容文档 + 索引。

## 七、对话流：双路并发 + 两段流式（2026-10-12 落地）

1. **前端"停顿"判据只能看正文有没有增长，不能看 phase**（`phases` 是累积数组、永不移除）
   → `streaming && hasContent && Date.now() - lastGrowAt > 1200`（400ms tick 驱动）。
2. **测试别写绝对毫秒门限**（首字延迟 6.5~12.7s 波动）→ 用相对判定（首字早于段间停顿点、不到整轮一半）。
3. **引用列表与检索开关必须同源同判**（同一 `localAdopted` 派生；余弦门槛 `rag.dual-path.pass1-cosine-gate: 0.28`）。
4. 日志 stdout 解码必须 `utf-8`；`web-search.pre-search-timeout-ms: 8000` 偏紧（Rerank 偶发 19.2s）
   → 建议 15000~20000（仍低于 `rag.dual-path.web-budget-ms: 30000`）。

## 八、Phase 4：Graphiti 记忆微服务集成

**定稿文档**：`Mini_Nodepad/docs/Phase4-Graphiti记忆微服务集成-架构设计方案-v3-Final.md`
（1607 行，含 §0.1~§0.7 决策索引 + 6 铁律、附录 A 契约速查、附录 B 实现陷阱 33 条、附录 D 实机验证项）。
**服务端** `http://192.168.88.130:8088`（VM，容器 `memory_graphiti` + `neo4j`）。
**顺序**：B0 契约层 ✅ → **B1 写入链（进行中）** → B2 图谱质量 → B3 召回注入 → B4 影子验收。

### 六条开发铁律（§0.7）
DB CAS 占位（废 Redis setnx，CAS 必须在 POST 前）／异步对账防死锁（`ConfirmJob` + `last_n` 按窗口量纲算）／
429·403 走长退避、不做 4xx 毒消息／背压只看 MQ 物理积压 + ReplayJob 闭环补投／
召回 `limit×4` 宽拉取 → 严过滤 → 再截断／提示词双通道（`userGlobalMemory` + `episodicMemory`
物理隔离、禁合并排序）。

### Java 侧契约（B0 ✅）
- `GraphitiMessage{ content, uuid(★新建一律 null), name(★确认锚点), roleType(必填), role(⚠️必填字段/可为 null),
  timestamp(⚠️OffsetDateTime 必须显式传), sourceDescription }`。
- `POST /messages {group_id, messages[]}` → 202 异步，**每条 message 独立一个 episode**。
- `POST /search {group_ids[], query, max_facts}` → **不过滤 invalid_at、返回体无 group_id**。
- `GET /episodes/{group_id}?last_n=N` → `last_n` 必填、纯时间倒序（排序键 `created_at` 落库时刻、
  **不是 `valid_at`**）、**无 offset/游标**；★ 返回 EpisodicNode 原始序列化（非 DTO）。
- ★ `Message.name` = **落库确认锚点**唯一载体（202 不含 uuid，`/episodes` 无过滤）→ 确认只能按 `name` 比对，
  命中时顺手回填本地 `graphiti_episode_uuid`。
- 有意偏离文档：`GraphitiProperties` 在 `properties/` 包（文档写 `config/`）；接口签名 `Long userId`（文档写 `String`）。
- ★ **通用坑**：自建 `RestClient` 时自己的 Jackson 转换器**必须 `add(0, ...)` 插首位**——默认列表里有
  `YamlHttpMessageConverter`（`supports()` 恒 true）→ 未指定 Content-Type 时请求体被序列化成 **YAML（首行 `---`）**
  → 服务端 pydantic 422。守卫用例 `writeBodyIsJsonNotYaml`。
- 输入上限自查：`lastN ≤ 500`、`query ≤ 2000`、`content ≤ 8000`。提交 `380423e`(feat) + `7b3467c`(chore)。

### B1 写入链（2026-10-13 落地并实机验证通过）
新增 `job/GraphitiReplayJob`（铁律 4「只延迟不丢弃」唯一执行者，**不写审计表**、熔断时整轮跳过）、
`service/HighRiskLexicon` + 规则快通道（`GraphitiScanProducer.dispatchHighRiskNow`，**必须显式 `setPriority(10)`**）等；
`ChatStreamService.persistMessages` 捕获 user 行 id 后调快通道。`application.yaml` 补
`graphiti.write/confirm/replay/backpressure` 五段；`MiniNodepadApplication` 补 **`@EnableScheduling`**；
`AssistantConfig` 补 **`memorySignalJudge` Bean**（此前缺失 → 启动即 NoSuchBeanDefinition）。
端到端闭环已验证（`synced_at` + `episode_uuid` 与 `/episodes` 逐字符一致，`confirm_rounds=0` 首轮命中）。

#### ⛔ 实机验证抓到的两个 P0（静态审查不可能发现，都静默失效）
1. **`LocalDateTime.MIN` 超出 MySQL DATETIME 定义域**（error 1525）：
   `GraphitiScanConsumer.resolveWindowStart` 用它当「无水位」哨兵 → **每个新会话首次扫描必炸**
   （首次扫描必走该分支）→ reject 不 requeue → 进 DLQ → 对外只表现为「记忆一直不生成」。
   → **Java 的极值常量不是 SQL 的极值常量**；`DATETIME` 只有 1000-01-01 ~ 9999-12-31。
   改用 `EPOCH_FLOOR = 1970-01-01`。
2. **跨表字符列排序规则不一致**（error 1267 `Illegal mix of collations`）：
   DDL 只写 `DEFAULT CHARSET utf8mb4` 没写 `COLLATE` → MySQL 8 默认 `utf8mb4_0900_ai_ci`，
   而本库既有表全是 `utf8mb4_unicode_ci`（RuoYi 老约定）→ `a.message_id = c.id` 列 vs 列比较抛错
   → **对账 SQL 每轮必炸 → 全走 maxRounds 被误判 CONFIRM_TIMEOUT → 写入链静默退化成「只投不认」**。
   ⚠️ **自查极难发现**：`existsByMessageId`（绑定变量 vs 列）**不会**报错（参数 coercibility=4 > 列=2）。
   **只有「列 vs 列」才暴露** → 新增表必须逐条检查跨表列比较。修法显式 `COLLATE=utf8mb4_unicode_ci`。

#### 方法论：三个「工具骗了自己」的坑
- **RabbitMQ `messages_ready` 是 5s 快照统计** → 判断「刚清空」会看到假残留。量真实条数要用
  `POST /api/queues/%2F/{q}/get` + `ackmode=ack_requeue_true`。
- **`/episodes` 只有路径形式** `GET /episodes/{group_id}?last_n=N`；写成 `/episodes?group_id=…`
  返回 **404**，看起来像「该用户没有数据」（我据此误判过「实测数据被清空了」）。
- **图谱有状态 + 服务端零去重** → 测试夹具用固定 id 时，上次运行遗留的 `cm_<id>` episode
  会让本轮「等落库」第一轮就命中 → **全绿但耗时是假的**。夹具第一步必须 `DELETE /group/{groupId}`。
- 三条易踩死：① ★★ `add_episode` 的 `uuid` 非 null = **update 已存在 episode**，查不到直接 NodeNotFoundError
  → **job 静默失败、零写入，而 HTTP 已返回 202**；新建必须 `uuid=null`。② ★★ episode 层**无任何向量能力**
  → 用 Graphiti 做"语义原话回溯"必死。③ ★ 中文 BM25 能命中但打分不可分（真命中 0.045 vs 噪音 0.035）。
- ★ `dispatched_at IS NULL` 有**三种语义**：尚未投递 / 判否（已进审计表）/ 毒消息或重试耗尽（已进审计表）
  → 两条对账 SQL 必须带 `NOT EXISTS(审计表)`。
- ★ 双字段状态机：`graphiti_dispatched_at`（兼 CAS 软锁，3 分钟自动放开）+ `graphiti_synced_at`（终态）。
  **CAS 必须前置**于 POST；**可重试失败必须回滚占位**，否则消息被自己的防重机制永久拒收。
- ★ 动态 `last_n`：`clamp(X + 10, 50, 500)`，`X` 量纲必须是 `dispatched_at` 而非 `created_at`。
- ★ **优先级是消息属性不是队列属性** → 自建 `Message` 重投必须显式 `setPriority`；`x-max-priority` 只在积压时生效。
- ★ `addTrustedPackages` 是包名**精确 equals**（非前缀），新消息类必须登记。
- DDL 已执行：`src/main/resources/db/phase4-b1-graphiti-write-chain.sql`（chat_message 加 4 列 + 2 索引 + 建 `graphiti_write_audit`）。
- 既有失败测试（与 B1 无关，全量 47 项里 3 项）：`AliOssUtilTest.testUploadInvalidBucket`、
  `PageServiceImplTest.testGenerateTitlePreview_ShortText_ShouldThrowException`、`SemanticPipelineDiagnosticTest.diagnose`（实机诊断类）。

### 测试方案的落点（**`docs/测试方案-后端全链路测试与量化基准.md`** = v2，2026-10-13 重构）

v1（`测试方案-B1写入链.md`）已入 `归档/`。v2 相对 v1 的三处实质变化：
① 新增 **HTTP 黑盒模态**（`RANDOM_PORT` + 真拦截器链，与浏览器同链路，**不占 8082**）；
② 新增 **L5 量化层**（每条用例必须打印数字 + 断言区间，布尔断言回答不了"快了多少"）；
③ 覆盖扩到 B1 之外（Phase 3 双路检索/两段流式/SSE、记忆、跨租户隔离）。

分层 **L0 纯函数 / L1 组件 Mockito / L2 HTTP 黑盒 / L3 真环境集成 / L4 混沌 / L5 量化**，约 100+ 用例。
**`pom.xml` 只有 `spring-boot-starter-test`**（无 Testcontainers / Awaitility / H2）→ 集成测试只能打真环境，
必须自带夹具清理 + `Assumptions` 断网门闸 + `@Tag("integration")` 分层（否则 `mvn test` 在无网环境必红）。

★ **HTTP 路线已证明可跑**：`ApiHttpSmokeTest`（`@Tag("api")`）**6/6 通过、32.3 秒** —— 真容器 + 真 JWT/Redis。
它顺带抓到既有 **P0 安全洞**：`GET /api/v1/test/mock-login` 无 `@Profile` 且不在拦截器 `addPathPatterns` 里
→ **任何 profile 下无需登录即可签发 `userId=1` 的 token**。已写成"钉子测试"，加固后该用例会红。

三条「不写就会重犯」的断言（v1 独有，别再丢）：
1. ★ 高危词表**混入空字符串** → `contains("")` 恒 true → 所有消息都走快通道、优先级机制彻底失效。
2. ★ **静态断言**：两条对账 SQL 必含 `NOT EXISTS(审计表)`；源码里**零** `LocalDateTime.MIN` 进 SQL 参数。
3. ★ **反向断言**：旧链 `semantic.scan.queue` 的 args **必须为空**（其消费者有 catch-all，加 DLX 是死代码）。

量化读数入口：`/actuator/metrics` 免登可读（**只暴露 metrics + health**）。注意 `retrieval.*` 是**惰性注册**的
→ 必须先跑一轮对话再读，断言要写"键存在或读数≥0"，**不能**写"列表里必须有它"。
`graphiti.*` 只有 2 个 Gauge 在启动期注册（`backpressure.active`、`write.queue.depth`）。

集成测试必须加 `-Djava.io.tmpdir=<可写目录>`，否则 surefire 建不出临时目录 → 测试 JVM 的 stdout 整段丢失。

**T1 已落地（2026-10-13，`16511d0`）**：`GraphitiErrorClassifierTest`(57) +
`GraphitiWriteMessageTest`(28) + `GraphitiStaticGuardTest`(19) = **101 用例全绿，单轮 <0.5s**。
★ 新形态 **静态约束测试**：读源码文本做断言（剥注释），专拦「编译通过、启动成功、只在特定输入下静默失效」
的结构性回归 —— `LocalDateTime.MIN` 不进 SQL、审计表必写 COLLATE、三条取数 SQL 必含
`NOT EXISTS(审计表)`（恰好 3 次，用 assertEquals 制造有意摩擦）、新链零 Redis 去重锁、
CAS 在 POST 前、可重试失败必回滚、旧链 `semantic.scan.queue` args 必为空、词表不得有空项。

写这类断言时的两个自踩坑（别重犯）：
1. **别断言三个分类谓词「互斥」**：`isRetryable` 刻意是「非毒消息」的**超集**，
   限流同时 `isRateLimited` 与 `isRetryable` —— 这条包含关系正是铁律 3 的落点，写成互斥就错了。
2. **setPriority 断言的适用范围要限定**：只有声明了 `x-max-priority` 的目的地才需要。
   `graphiti.scan.delay.queue` 是 TTL 延时队列、无优先级，给它设优先级无效 —— 过宽的断言会误报。

本轮生成的三个发现（**未改产品代码**，按「不扩大任务范围」处理）：
1. 字符截断逻辑在**三处**各写一遍（`ScanConsumer.budget` / `ReplayJob.budget` / `ScanProducer` 内联），
   常量已统一，但截断体是三份副本；已用静态断言钉住「必须引用同一常量」。
2. 三处截断都用 `substring(0, max)`，**可能切断代理对**（emoji 落边界 → 孤立高代理；
   影响有限：UTF-8 编码降级为替换字符、不抛异常）。
3. 对账 SQL 的 `INTERVAL 3 MINUTE` 是**硬编码魔数**，与 `graphiti.confirm.interval-ms` 无代码级关联。

**Java lambda 捕获坑**（写参数化/循环断言必踩）：for 循环变量与循环内被重新赋值的局部变量
**不是 effectively-final**，进 lambda 会编译失败 → 先复制成 `final` 再断言。


## 九、Graphiti 服务端 / VM / Docker（实机实测，非推测）

- 写 `POST /messages`；检索 `POST /search`（中文放 body）。**无 POST /episodes、无 user_id、无 source 字段**；
  `messages[]` 缺失 = 422；`Message.role` **无 default = 必填**（可 null 但字段必须出现）。
- ⚠️ `/search` 的 `group_ids` 可选可 null：**漏传、或写成单数 `group_id` = 跨租户全局检索且零报错**；
  返回 fact **不含 group_id** → 服务端应对空 group_ids 返回 400（**尚未加**）。
- ⚠️ `POST /clear` 无参数 = **整图清空**；按组清理用 `DELETE /group/{group_id}`。
- ⚠️ `/search` 与 `/get-memory` **都不过滤已作废事实** → 客户端必须 `invalid_at is None` 过滤。
- ⚠️ 边标签由模型自由生成，**Cypher 不能硬编码关系名**。
- `FactResult` 字段：`uuid, name, fact, valid_at?, invalid_at?, created_at(必填), expired_at?, source_node_uuid,
  target_node_uuid, episodes[]`；**无 group_id**；时间序列化 = 微秒 + offset → Java 侧**必须 `OffsetDateTime`**。
  另有 `GET /entity-edge/{uuid}`、`DELETE /episode/{uuid}`。
- ★ `/search` 丢 episodes 只返 edges；`fact.episodes[]` + `GET /episodes/{group_id}` 反查原话 = **记忆溯源**。
- 部署：compose 在 `/root/graphiti-memory`；`ingest.py` 实为 `routers/ingest.py`（COPY 错路径静默失效）。
  改 compose 用 `docker-compose.override.yml`（⚠️ ports/volumes 是列表追加，**override 里绝不能写 ports**）。
- ⚠️ 换 embedding 模型**必须清库**（同 1024 维不报错，但向量空间不同 → 相似度静默失真）。
- ★ 模型分水岭：`qwen-plus` 才有教科书级双时间戳裁决；`qwen-turbo` 会降级甚至自相矛盾。
- 服务端 4 根因（v2 镜像已修）：① embedder 未接线；② 默认 `OpenAIClient` 走 OpenAI Responses API
  （第三方端点不支持）→ 必须换 `OpenAIGenericClient`；③ FastAPI `finally: client.close()` 提前关连接；
  ④ ingest worker 只 `except CancelledError`，异常静默杀死循环。
- ★ 改环境变量无用（pydantic `extra='ignore'` 静默丢配置）→ 判定配置生效**只看服务实际 config 对象**。
- ★ 换 LLM client 必须**同时**赋值 `client.llm_client` 与 `client.clients.llm_client`（`add_episode` 读后者）。
- DashScope 支持原生严格 `json_schema`；`json_object` 会让模型把 schema 本身当答案。

### Docker Engine API over TCP（`http://192.168.88.130:2375`，**无 TLS 无鉴权 = root**）
- 列表 `/containers/json?all=1`、`/logs?stdout=1&stderr=1&tail=N`、`/start`、`/archive?path=`、`DELETE /images/{id}`。
- **日志是 mux 帧**，必须读原始 bytes 再切帧；`/exec/{id}/start` **返回多路复用二进制流（8 字节头），不是 JSON**。
  exec 到已停止容器返回 409。
- 传文件：`sh -c "echo '<base64>' | base64 -d > /tmp/x.py"`；别用带分号的 `python -c`。
- ★ 远程构建 `POST /build?t=<tag>`（body=tar）；层会被宿主机 `compose --build` 命中 CACHED。
- ★ **非 root 用户 + PATH ≠ 可执行**：PATH 含该目录但非 root 跑 `bash -c` 报 127 → 一律绝对路径。
- ★ **基底 `USER` 非 root 时 `COPY` + `RUN chmod` 必失败** → `COPY --chmod=0755`。
- ★ **启动竞速**：graphiti 比 neo4j 只晚 1~2s → `build_indices_and_constraints` 抛 ServiceUnavailable → exit 3；
  `RestartPolicy=no` 时对外只表现为 **502**。解法：入口 wait-for-neo4j + 两服务 `restart: unless-stopped`。
  实测冷启动 110.9s 就绪。

### VM 网络 / 暴露面
- **NAT 不是桥接**：宿主机 192.168.88.1 = VMware VMnet8；虚机 .130；外部物理网络无法主动入站。
- 34 个端口绑 `0.0.0.0`（3306/6379/6333,6334/7474,7687 弱口令 password123/5672,15672/8088 等）。
- ★ 重启 dockerd 会掉 6 个 `restart=no` 容器（qdrant / redis-stack / rabbitmq×2 / postgres-db 等）。
- 宿主机 CentOS 7（已 EOL）、Docker 26.1.4、2 vCPU / 2.9GB。
- ★ **firewalld 挡不住 Docker 发布端口**（走 PREROUTING DNAT → FORWARD/DOCKER-USER），只能拦 INPUT（如 2375）
  → 限制容器端口用 `iptables -A DOCKER-USER`，或把 ports 写成 `192.168.88.130:3306:3306`。
- ★★ **DOCKER-USER 白名单的经典自伤**：`-i ens33 ! -s 192.168.88.0/24 -j DROP` 会切断**所有容器出网**
  （回包源是公网 IP，被同一条规则 DROP）。诊断特征：**出网 TCP 全 timeout，但 DNS 正常、
  容器间与宿主机→容器正常**。正确写法必须限连接状态：
  `-I DOCKER-USER 1 -i ens33 -m conntrack --ctstate NEW ! -s 192.168.88.0/24 -j DROP`。
  复位：`iptables -F DOCKER-USER && iptables -A DOCKER-USER -j RETURN`。

### 基础设施待办（未收口）
- 🔴 Docker 2375 无 TLS 无鉴权（等价 root）。🔴 `/search` group_ids 可空漏传 = 跨租户检索（服务端未加校验）。
- 🟠 `semantic.scan.queue` 未声明 DLX（**刻意**：其消费者有 catch-all，异常永不外泄 → 加 DLX 是死代码）。
- 🟡 compose 里 API key 明文；🟡 CentOS 7 已 EOL。

## 十、WorkBuddy 自身数据目录（2026-10-10 升级事故）

- `C:\Users\mfYang\.workbuddy\` = **5.6.2 及以前**数据目录（本项目 10-08 起主线会话都在此）；
  `C:\Users\mfYang\.workbuddy-ai\` = 5.7.7 起新目录。安装目录 `E:\workbuddy\WorkBuddyAI` 与数据无关。
- 升级 5.7.7 时本地迁移失效（迁移脚本只认本机为空的 `%APPDATA%\WorkBuddy\codebuddy-sessions.vscdb`）
  → 新库为空 → **侧边栏会话列表清空**（`migration-*.log` 里 10 步全 `skipped` 但状态写 `SUCCESS`）。
  排查：`logs/AppStartup.log`（找 `reason=update`）、`logs/migration/`、`workbuddy.db` 的 `sessions` 表、
  `user-*/sidebar-list-snapshot.json`。
- **规矩：升级前手动备份整个 `C:\Users\mfYang\.workbuddy\`**；排查"会话丢失"先看旧目录
  `workbuddy.db` + `projects/*/**.jsonl`，会话内容与配图（`clipboard-images/`）通常还在。
