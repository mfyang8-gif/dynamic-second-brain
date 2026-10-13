# 项目长期记忆

> **两个工程**：后端 `D:\ruoyi\RuoYi-Vue-master\Mini_Nodepad`（RuoYi 风格 Spring Boot 3.4 + Java 21）；
> 前端 `D:\ruoyi\RuoYi-Vue-master\mini-notebooklm-web`（Vue 3.5 + TS 5.9 + Vite 8 + Pinia 4 + reka-ui + Tailwind v4）。
> 前端辅助脚本 `_*.py`（`_tsc.py` / `_build.py` / `_check_css.py`）都在**前端目录**，别在后端找。
> **动手前先确认会话 CWD**（见第四节的踩坑）。

## 一、前端架构与 CSS 铁律

- Tailwind v4 CSS-first，**无 `tailwind.config.js`**；主题写在 `src/styles/index.css` 的 `@theme inline`
  （用户按 v3 习惯说"改 tailwind.config.js"时，指的就是这里）。
- 三层 CSS：`primitives.css`（`--x-*` 原语，三风格唯一实现处）→ `tokens.css`（语义层，变量名冻结）
  → `index.css`（`@theme inline` + 组件类）。组件只消费语义类，换风格只改原语层。
- 三正交维度：`data-theme`(light/dark) × `data-style`(apple/swiss/editorial) × `data-accent`(7 色)。
  强调色只对 apple 开放（Swiss 只用黑白红，Editorial 严禁彩色）。

### 反复踩过的坑（必须遵守）

1. **`font-serif` 默认是 ui-serif/Georgia** —— 只改原语没用，必须在 `@theme inline` 写
   `--font-serif: var(--x-stack-serif)`，否则模板 `class="font-serif"` 永远落不到思源宋体。
2. **`@theme inline` 只认 `--color-*` 键名**。`--color-ink-invert` 只能用 `text-ink-invert`；
   `text-fg-invert` 是**不存在的类**，不生成 CSS、静默失效（曾致思维导图根节点纯黑无字）。
   要用就补同值别名 `--color-fg-invert: var(--fg-invert)`。
3. **压缩器会把同一选择器拆成多个规则块**。核对产物时同名选择器要**拼接**（setdefault + +=），
   dict 赋值会刷出一片假 MISS。等价写法：`#ff0000`→`red`、`#ffffff`→`#fff`、`150ms`→`.15s`、选择器引号被去掉。
4. **同一文件的多个 Edit 并行下发会互相覆盖**（只最后一个生效，但都报 success）。
   必须**串行**改，改完回读 `dist` / `dist-smoke` 产物核对实际值（typecheck/build 覆盖不到 CSS 变量值）。
5. **可选边框别用 `border-bottom: var(--x, none)` 简写开关** —— 变量为 none 会把整条边框杀掉、
   误杀其余风格（曾致导图所有节点没底边、相邻叶节点连成"空盒"）。
   正确：完整 border 担宽度/样式，可选那一边只用 `border-bottom-color` 长手覆色。
6. **`:root` 的 `--x-*` 会沿继承链渗入 `[data-style]` 段**，`var()` fallback 只在整条链都未定义时才生效
   （曾致 Editorial 叶节点继承 `:root` 强调色底、凭空长灰底）。
   规矩：每个 `[data-style]` 段**显式写全**自己需要的风格变量。

## 二、区块拆解与设计文档

### 思维导图沉浸态（设计文档第 16 章）
- **放大态画布零图形库**（不引 D3/markmap —— 会把节点画成 SVG `<text>`，无法复用 `.mm-node` 三风格原语）。
  纯 Vue 模板 + CSS `transform`：节点是真实 `<button>`，连线是 SVG `<path>`（三次贝塞尔），缩放只改 world transform。
- 连线线宽不随缩放变粗靠 `vector-effect: non-scaling-stroke`。
- 布局算法是纯函数 `utils/mindmap-layout.ts`（tidy-tree：后序行分配 + 同层左边缘对齐）。
- **叶节点 = 提问入口** → 右栏切 `AiCopilot`（复用同一条会话）。右栏视图态在 `stores/layout.ts`
  的 `rightPanelView`（`'studio' | 'copilot'`）；"能否提问"四道闸抽在 `composables/useAskGate.ts`。
- 画布模板**零 `data-style` 判断**，三风格差异全在 `--x-mm-*` 原语。
- 缩小态（右栏抽屉）= VS Code 级竖排缩进树（`MindmapNode.vue`）。

### MD3 悬浮岛（apple 主题）
- apple = MD3：画布 `#F0F4F9`（暗 `#131314`）+ 三座白岛（暗 `#1E1F20`）24px 圆角 + `ring-black/5`；
  右栏 `grid-cols-2` 胶囊工具箱 + tonal 五色（导图 blue / 测验 green / 闪卡 amber / 报告 purple / 演示 rose，
  暗色 -900/30 反转）；左栏类型图标**绝对中性灰**（浅 `#f1f3f4` / 暗 `#2A2B2D`），彩色只给右栏。
- 图标：`unplugin-icons` + `@iconify-json/material-symbols`（Rounded 后缀），编译期 SVG，
  尺寸靠 CSS `font-size` 下发（不是 `:size`）。
- ⚠️ **风格几何变量（`--x-island-pad/gap`）必须放 `[data-style]` 基础段**，明暗段只放颜色 ——
  写进 light 段会让暗色下画布 padding 归零、三岛贴死（只有量测能逮到，截图看不出）。
- 卡片副标题 = hover 深色 Tooltip，必须落在卡片横向范围内（滚动容器会裁越界浮层）。

### 面板开合与右侧轨道（第 15 章）
- **左栏开合入口只有中栏顶栏的 `PanelToggle`**；左栏栏头 `←` 与左边缘把手已删除，**别加回来**
  （三个入口干同一件事 = 用户说的"有两个返回按钮"）。
- **右侧抽屉必须贴死视口右缘**（`rightPanelWidth`：collapsed→`--rail-width`，其余→`--panel-width`）。
  "轨道常驻 + 抽屉让位到轨道左侧"已被用户否掉并回滚。
- **Swiss 开关闲置态 = 实心墨块**（`background-color: var(--fg)`，暗色自动反相），**不要画汉堡三条线**。
- 右侧 `<aside>` **不能加 `overflow-hidden`**（轨道 hover 长不出去）；裁切只放内层"只包抽屉"的窗口。
- 三套开关形态全靠 `[data-style]` 在 CSS 切换，组件模板零风格判断。

### 浮层 vs 面板：Editorial 透明性分界（第 10 章）
- Editorial 把 `--x-card` / `--x-panel` 设 transparent 是**面板**（栏/岛）的纸张感；
  **浮层（弹窗 / 菜单 / 气泡 / 提示条 / 闪卡面）必须实心**，否则与底下内容叠字
  （曾致配置弹窗、外观浮层、书库菜单全部看不清）。
- 规矩：所有浮层挂 **`.overlay-surface`**（AppDialog 是所有弹窗的底座，挂一处覆盖全部 Dialog）；
  Editorial 覆写段写在 index.css **顶层**（脱 @layer 才盖得过 utilities 的 border/shadow）：
  象牙白 #fcfaf2 + 颗粒纸纹 + 铅笔灰线 rgb(42 43 45/.15) + 硬边错落纸影（0 模糊）+ 2px 圆角；暗色 #26241f。
  变量 `--x-overlay-paper/line/edge/grain`。**新增浮层必须挂它**（`_check_css.py` 有挂类清单断言）。
- `--x-card` / `--x-panel` 的透明是设计意图，反向断言锁死，别顺手改实。
- Apple 书库网格卡 = MD3 outlined：`.book-card` + `--x-outline-variant`（浅 #c4c7c5 / 暗 #444746），
  hover 边框不动只沉底色（`--x-hover`）。

### 设计文档
`mini-notebooklm-web/docs/多主题架构设计-风格与主题色.md`
（13 章：字体 CDN 化 / Swiss 硬约束；14 章：路由过渡白屏根因；15 章：面板开关与右侧轨道；
16 章：思维导图沉浸态 / 面包屑 / Copilot 右栏）。**改风格或动三栏骨架前先读它。**

## 三、路由过渡白屏（⛔ 绝不能改回去）

`src/App.vue` 的路由出口：

```html
<RouterView v-slot="{ Component, route }">
  <Transition name="route">
    <component :is="Component" v-if="Component" :key="route.path" />
    <div v-else>正在加载页面…</div>
  </Transition>
</RouterView>
```

- **这个 `<Transition>` 永远不能加 `mode="out-in"`**：Vue 3.5 + vue-router 5 下会拿到 `undefined`
  插槽值、渲染成注释占位符并**永久停住** → 点「书库」整页纯白、**控制台零报错**、刷新才好。
  加 `:key` 无效（已实测）。不能带 out-in，所以过渡期新旧两页同时在场 →
  `.route-leave-active` 用 `position: absolute` 把离开态脱离文档流。
- **配套铁律**：`views/*` 的模板根元素在组件存活期间**绝不允许**被内部 v-if/v-else 换掉 ——
  Transition 离开时只认【第一次挂载】的根 el。NotebookView 曾把 `v-if="ready"` 写在根上，
  致点「返回书库」后新旧页同时存在、URL 已变但界面卡住，且**零报错**。
  需要二态就套一层 `<div class="route-shell">` 稳定外壳。
- 取证：`_cdp_route_repro.mjs`。**白屏 + 控制台干净 = 去看 DOM 有没有元素**，别猜 JS 报错。

## 四、环境陷阱（本机特有）

- ⚠️ **会话 CWD 不一定在本工程**：2026-10-12 的 Phase 4 全部工作，CWD 实为 `D:\7-Zip\springai_tes`，
  记忆落在了那边。**动手前先确认 CWD**；不是 `Mini_Nodepad` 时用绝对路径写本项目记忆并提醒用户切换。
- **Bash 工具 PATH 是坏的**（`ls` / `find` / `head` 全 command not found，复合命令里 `cd` 不生效）→
  用 Glob/Read，或"绝对路径 + Python/Node"，别写 `cd A && cmd`。
  PowerShell 工具**不回显 stdout**，要看输出就用 Python 打印。
- **代理**：Bash 环境自带 `http_proxy`（127.0.0.1:2650 或 13514，端口随会话变）——
  python urllib 走代理、Node fetch 不走 → 同一地址两者结论可能相反。
  python 加 `env -u http_proxy` 或 `ProxyHandler({})`；curl 用 `--noproxy '*'`。
- **npm 必须绕代理**：全局 `~/.npmrc` 配了失效代理端口 → `ECONNREFUSED`（表现为长时间无输出）。
  用 Python subprocess 调 npm，清 `HTTP(S)_PROXY` / `ALL_PROXY`（含小写）+ `--proxy '' --https-proxy ''`
  + `--registry https://registry.npmmirror.com`。
- **Java / Maven**：`JAVA_HOME=D:\java`，Maven 3.9.4 在
  `/d/maven/apache-maven-3.9.4-bin/apache-maven-3.9.4/bin`。离线编译 `mvn -o -DskipTests compile`。

### dev server 与 8082
- 后端 dev 端口 **8082**（`src/main/resources/application-dev.yaml`，默认 profile = dev；前端 vite proxy 指它）。
  配置文件后缀是 **`.yaml` 不是 `.yml`**。
- ⚠️ **从工具侧起的前端活不过回合结束**（bash `&` / `run_in_background` / DETACHED_PROCESS 全被回收；
  后端 8082 反而能活）→ **前端必须由用户自己在窗口里起**，用户报"用不了"先按此前排查，别反复重启。
- 给用户的双击脚本（前端目录，纯 ASCII 防 cmd 代码页乱码）：`start-dev.bat`（自探测 node，
  直接 `"<node>" node_modules\vite\bin\vite.js`，**不走 npm**）、`start-backend.bat`、`start-all.bat`。
- ⚠️ **工具侧起过 8082 后收尾必须 `taskkill`**，否则用户回 IDEA 启动报 "Port 8082 was already in use"。
  占用排查：`netstat -ano | grep ":8082.*LISTENING"` → `tasklist` 对内存（Spring Boot 500MB+，
  IDEA 的 java 偏小，**别误杀 IDE**）。`_run8082.py` 重复调用会静默写 `_back.log`（端口占用 BUILD FAILURE，
  不是编译错误）。

## 五、CDP 探针工具（真 Chrome，零依赖）

本机无 `agent-browser` CLI，但 Chrome 在 `C:\Program Files\Google\Chrome\Application\chrome.exe`，
Node 22 自带 `WebSocket` → headless Chrome + CDP。测试账号 `demo / demo12345`，
token 存 localStorage 的 `mnl.token`。

- `_cdp_probe.mjs`：通用 7 步导航 + DOM 体检 + 截图（设 `MNL_TOKEN` / `MNL_BOOK` / `MNL_OUT`）。
- `_cdp_theme_shot.mjs`：三风格「创作入口 / 来源行 / 添加来源」逐槽位量测 + 截图。**改三栏视觉后必跑**。
  登录走 `/api/v1/auth/login`，**authorization 头是裸 JWT、不带 Bearer**。
- `_cdp_mindmap.mjs`：导图专用（`CANVAS_PROBE`：节点/连线计数、根形态、底纹、zoom、右栏是否被 Copilot 接管）。
- `_cdp_style_shot.mjs`：验证 **hover 才出现**的样式（CDP `Input.dispatchMouseEvent` 移**真指针**，
  改 class 触发不了 `:hover`）。
- `_cdp_md3_shot.mjs`：MD3 悬浮岛专用（apple×明暗全量 + Swiss/Editorial 隔离守卫）。**动 apple 段后跑它**。
- `_png_lines.py`：最小 PNG 解码器扫长直线，把用户说的"那条线"定位到像素坐标与颜色
  （**先量再问，别猜**；配合编号示意图让他点，一次就问准）。

⚠️ 取证要点：`document.body.innerText` 会被 DOM 注释污染，硬指标要取真实元素文本。
页面会加载历史消息，断言必须收敛到**最后一条 `.cm-ans`**（否则把历史气泡一起数进来）。

## 六、写注释的硬规矩
- CSS 注释正文**不要出现 `*/`**（会提前终止块注释）。
- HTML 注释正文**不要出现连续两个短横线**（注释当场闭合，后半段变成页面上的可见文字）。两条都实际踩过。

## 七、文档组织约定（2026-10-11 定）

- 两个 docs（后端 `Mini_Nodepad/docs`、前端 `mini-notebooklm-web/docs`）各自只留**现行**文档，
  各有一份 `文档索引.md` 登记「待办 / 现行 / 归档」三张表。
- 已完成 / 已失效的文档移到同目录 `归档/`（保留可追溯，但不再当依据引用）。
  已归档 10 份：审核-历史消息接口实现、审核-工件动态命名改动清单、审核-动态命名核心Diff、
  白皮书 v3.0、主对话框优化方向、后端对话流修复清单、后端接口实现清单-来源管理、
  后端修改建议清单、Studio 来源管理与生成面板改造方案、Studio 工件渲染与配置面板。
- **唯一真·待办**：`Mini_Nodepad/docs/后端待办清单.md`（引用原文 / 重新生成 / 赞踩反馈）。
  2026-10-13 复核：**三项端点均已实现**并随 `c929737` 提交
  （`ChunkReferenceController` / `RegenerateDTO` / `MessageFeedback`）→ **该清单可销项**。
- 新增文档顺手在 `文档索引.md` 里登记一行。

## 八、对话流：双路并发 + 两段流式（2026-10-12 落地）

1. **前端"停顿"判据只能看正文有没有增长，不能看 phase**（`phases` 是累积数组、某 phase 到达后永不移除；
   实测真正让用户干等 10~25s 的是 **Pass2 模型首字延迟**，不是"等联网" —— Pass1 收尾与联网返回只差几十毫秒）。
   正确写法：`streaming && hasContent && Date.now() - lastGrowAt > 1200`（400ms tick 驱动重算）。
2. **测试里不要写绝对毫秒门限**（doubao-seed-2-0-pro 首字延迟 6.5~12.7s 波动，会支配一切时间指标）
   → 用相对判定：首字早于段间停顿点、且不到整轮耗时的一半。
3. **引用列表与检索开关必须同源同判**：低于余弦门槛（`rag.dual-path.pass1-cosine-gate: 0.28`）的
   本地片段既不能进 Pass1、也不能进引用列表，两者由同一个 `localAdopted` 判据派生。
- 日志：`_mvn.py` 的 stdout 解码必须是 `utf-8`（原为 mbcs → 中文在落盘前就不可逆乱码）；
  `application.yaml` 另加 `logging.charset.console/file: UTF-8`。
- `web-search.pre-search-timeout-ms: 8000` 偏紧（Aliyun Rerank 偶发 19.2s）→ 建议提到 15000~20000
  （仍低于 `rag.dual-path.web-budget-ms: 30000`）。
- 可复用测试资产：`_e2e_dualpath_suite.mjs`(13)、`_e2e_dualpath_degrade.mjs`(2)、
  `_run8082_degraded.py`、`_guard_probe.java`(24)、`_probe_dualpath_ui.mjs`（三主题）。

## 九、Phase 4：Graphiti 记忆微服务集成

**定稿**：`Mini_Nodepad/docs/Phase4-Graphiti记忆微服务集成-架构设计方案-v3-Final.md`
（1607 行，含 §0.1~§0.7 决策索引 + 6 条铁律、附录 A 契约速查、附录 B 实现陷阱 33 条）。
**服务端**：`http://192.168.88.130:8088`（VM，容器 `memory_graphiti` + `neo4j`）。
**实施顺序**：B0 契约层 ✅ → B1 写入链 → B2 图谱质量 → B3 召回注入 → B4 影子验收。

### Java 侧契约
- `GraphitiMessage{ content, uuid(★一律 null), name(★确认锚点), roleType(必填),
  role(⚠️必填字段 / 可为 null), timestamp(⚠️OffsetDateTime，必须显式传), sourceDescription }`
- `POST /messages {group_id, messages[]}` → 202 异步，**每条 message 独立成一个 episode**。
- `POST /search {group_ids[], query, max_facts}` → **不过滤 invalid_at、返回体无 group_id**。
- `GET /episodes/{group_id}?last_n=N` → `last_n` 必填、纯时间倒序（排序键 = `created_at` 落库时刻，
  **不是 `valid_at`**）、**无 offset / 游标** → 查不到老 episode；时间格式 `...Z`
  （`/search` 是 `+00:00`，`OffsetDateTime` 两者都能吃）；未知参数被静默忽略。
  ★ 返回的是 **EpisodicNode 原始序列化（非 DTO）**。
- ★ `Message.name` 是「**落库确认锚点**」的唯一载体：202 响应不含服务端生成的 uuid，`/episodes` 又无过滤 →
  确认只能按 `name` 比对，命中时顺手回填本地 uuid（零额外请求）；`source_description` 专心写人话式来源说明。

### 三条最容易踩死的实测结论
1. ★★ `add_episode` 的 `uuid` 非 null = **update 已存在的 episode**，查不到直接 `NodeNotFoundError`
   → **job 静默失败、零写入，而 HTTP 已返回 202**。新建必须 `uuid=null`。
2. ★★ episode 层**没有任何向量能力**（枚举只有 bm25、`EpisodicNode` 无 embedding、Neo4j 无 VECTOR 索引）
   → 用 Graphiti 做"语义原话回溯"必死。
3. ★ 中文 BM25 **能命中**但打分不可分（真命中 0.045 vs 噪音 0.035）→ 不存在可用阈值。

### 六条开发铁律（§0.7）
DB CAS 占位（废 Redis setnx，CAS 必须在 POST 前）／异步对账防死锁（`ConfirmJob` + `last_n` 按窗口量纲算）／
429·403 走长退避、不做 4xx 毒消息／背压只看 MQ 物理积压 + ReplayJob 闭环补投／
召回 `limit×4` 宽拉取 → 严过滤 → 再截断／提示词双通道（`userGlobalMemory` + `episodicMemory`
物理隔离、禁合并排序）。

### B0 契约层 ✅ 已落地（2026-10-13）

新增 `client` 包（10 个 DTO + Client 接口 + Impl + `GroupIdResolver` + `GraphitiClientException`）、
`properties/GraphitiProperties`、`application.yaml` 的 `graphiti.*` 段（三开关默认全关，**工程内零调用点**）。
测试 `GraphitiContractTest` **20 个用例，离线可跑**（JDK HttpServer 起 stub，不依赖真 Graphiti）。

- ★ **通用坑**：自建 `RestClient` 的 messageConverters 时，自己的 Jackson 转换器**必须 `add(0, ...)`
  插到首位，不能 append** —— 默认列表里有 `YamlHttpMessageConverter`（snakeyaml 在 classpath 即注册，
  `supports()` 恒 true），未显式指定 Content-Type 时 RestClient 取第一个 `canWrite` 的转换器
  → 请求体被序列化成 **YAML（首行 `---`）**，服务端 pydantic 直接 422。
  守卫用例 `writeBodyIsJsonNotYaml`（断言首行非 `---`、可 readTree、Content-Type 含 json），
  已反向验证过：改回 append 立刻转红。
- 自查补了 3 个输入上限：`lastN ≤ 500`（`/episodes` 无分页）、`query ≤ 2000`、`content ≤ 8000`。
- **有意偏离文档**（别照文档找文件）：`GraphitiProperties` 在 `properties/` 包（文档写 `config/`）；
  接口签名 `Long userId`（文档写 `String`）。
- **`/healthcheck` 端点未经实机验证**，B1 首次联调需确认（或改 `/docs`）。

## 十、Graphiti 服务端 / VM / Docker（实机实测，非推测）

### 服务契约细则（v2 镜像已验收）
- 写 `POST /messages`；检索 `POST /search`（中文放 body）。**无 POST /episodes、无 user_id、无 source 字段**；
  `messages[]` 缺失 = 422；`Message.role` **无 default = 必填**（可 null 但字段必须出现）。
- ⚠️ `/search` 的 `group_ids` 可选可 null：**漏传、或字段名写成单数 `group_id` = 跨租户全局检索且零报错**；
  返回的 fact **不含 group_id** → 服务端应对空 group_ids 返回 400（**尚未加**）。
- ⚠️ `POST /clear` 无参数 = **整图清空**；按组清理用 `DELETE /group/{group_id}`。
- ⚠️ `/search` 与 `/get-memory` **都不过滤已作废事实** → 客户端必须 `invalid_at is None` 过滤。
- ⚠️ 边标签由模型自由生成，**Cypher 不能硬编码关系名**。
- `FactResult` 字段：`uuid, name, fact, valid_at?, invalid_at?, created_at(必填), expired_at?,
  source_node_uuid, target_node_uuid, episodes[]`；**无 group_id**；时间序列化 = 微秒 + offset
  → Java 侧**必须 `OffsetDateTime`**（全局 Jackson 把它钉成无 offset 格式，必然解析失败）。
  另有 `GET /entity-edge/{uuid}`、`DELETE /episode/{uuid}`。
- ★ `/search` 丢 episodes 只返 edges；`fact.episodes[]` + `GET /episodes/{group_id}` 反查原话
  = **记忆溯源**（零新增设施，但只能溯源已抽成事实的对话）。
- 部署：compose 在 `/root/graphiti-memory`；`ingest.py` 实为 `routers/ingest.py`
  （COPY 错路径会静默失效）。改 compose 用 `docker-compose.override.yml`
  （⚠️ ports / volumes 是列表追加，**override 里绝不能写 ports**）。
- ⚠️ 换 embedding 模型**必须清库**（同 1024 维不报错，但向量空间不同 → 相似度静默失真）。
- ★ 模型分水岭：`qwen-plus` 才有教科书级双时间戳裁决；`qwen-turbo` 会降级甚至自相矛盾。

### 服务端 4 个根因（v2 镜像已修）
① embedder 未接线；② 默认 `OpenAIClient` 走 OpenAI Responses API（第三方端点不支持）
→ 必须换 `OpenAIGenericClient`；③ FastAPI `finally: client.close()` 提前关连接；
④ ingest worker 只 `except CancelledError`，异常静默杀死循环。
- ★ 改环境变量无用（pydantic `extra='ignore'` 静默丢配置）→ 判定配置是否生效**只看服务实际 config 对象**。
- ★ 换 LLM client 必须**同时**赋值 `client.llm_client` 与 `client.clients.llm_client`（`add_episode` 读后者）。
- DashScope 支持原生严格 `json_schema`（不兼容的只是 Responses 端点）；`json_object` 会让模型把 schema 本身当答案。

### Docker Engine API over TCP（`http://192.168.88.130:2375`，**无 TLS 无鉴权 = root 权限**）
- 列表 `/containers/json?all=1`、`/logs?stdout=1&stderr=1&tail=N`、`/start`、`/archive?path=`、`DELETE /images/{id}`。
- **日志是 mux 帧**，必须读原始 bytes 再切帧。exec 到已停止容器返回 409。
- 传文件：`sh -c "echo '<base64>' | base64 -d > /tmp/x.py"`；别用带分号的 `python -c`。
- ★ 远程构建 `POST /build?t=<tag>`（body=tar，结果看 aux.ID / error）；层会被宿主机 `compose --build` 命中 CACHED。
- ★ **非 root 用户 + PATH ≠ 可执行**：PATH 含该目录但非 root 跑 `bash -c` 报 127
  （POSIX 目录穿越权限：uv 只在 `/root/.local/bin`，而 `/root` 是 `drwx------`）。
  迷惑点：镜像自带 CMD 能跑通（runc 以 root 解析并 fexecve 启动 PID 1）。
  启动脚本一律绝对路径、优先 venv 内可执行文件；restart + 脚本早退 = 无限重启循环，日志像"卡在等依赖"。
- ★ **基底 `USER` 非 root 时 `COPY` + `RUN chmod` 必失败**。修法：`CMD ["/bin/bash","/app/script.sh"]`
  删掉 chmod，或 `COPY --chmod=0755`。反推：声称 Dockerfile 构建成功、里面却有 `RUN chmod` 的，
  多半是 `docker commit` 产物。
- ★ **启动竞速**：graphiti 比 neo4j 只晚 1~2s → `build_indices_and_constraints` 抛 ServiceUnavailable
  → exit 3；`RestartPolicy=no` 时对外只表现为 **502**。解法：入口脚本 wait-for-neo4j +
  两服务都 `restart: unless-stopped`。实测冷启动 110.9s 就绪。

### VM 网络 / 暴露面
- **NAT 不是桥接**：宿主机 192.168.88.1 = VMware VMnet8；虚机 .130；ARP 实测仅这两台 → 外部物理网络无法主动入站。
- 34 个端口绑 `0.0.0.0`（3306 / 6379 / 6333,6334 / 7474,7687 弱口令 password123 / 5672,15672 / 8088 等）。
- ★ 重启 dockerd 会掉 6 个 `restart=no` 容器（qdrant / redis-stack / rabbitmq×2 / postgres-db / youthful_archimedes）。
- 宿主机 CentOS 7（已 EOL）、Docker 26.1.4、2 vCPU / 2.9GB。
- ★ **firewalld 挡不住 Docker 发布端口**（走 PREROUTING DNAT → FORWARD/DOCKER-USER），
  只能拦 INPUT（如 2375）；限制容器端口用 `iptables -A DOCKER-USER`，
  或把 ports 写成 `192.168.88.130:3306:3306`。
- ★★ **DOCKER-USER 白名单的经典自伤**：`-i ens33 ! -s 192.168.88.0/24 -j DROP`
  会切断**所有容器出网**（回包源是公网 IP，被同一条规则 DROP）。
  诊断特征：**出网 TCP 全 timeout，但 DNS 正常、容器间与宿主机→容器正常**，三者同时成立 = FORWARD/DOCKER-USER 被拦。
  正确写法必须限连接状态：
  `-I DOCKER-USER 1 -i ens33 -m conntrack --ctstate NEW ! -s 192.168.88.0/24 -j DROP`。
  复位：`iptables -F DOCKER-USER && iptables -A DOCKER-USER -j RETURN`。

### 基础设施待办（未收口）
- 🔴 Docker 2375 无 TLS 无鉴权（等价 root）。🔴 `/search` 的 group_ids 可空漏传 = 跨租户检索（服务端未加校验）。
- 🟠 `semantic.scan.queue` 未声明 DLX；🟡 compose 里 API key 明文；🟡 CentOS 7 已 EOL。

## 十一、WorkBuddy 自身数据目录（2026-10-10 升级事故）

- `C:\Users\mfYang\.workbuddy\` = **5.6.2 及以前**的数据目录（本项目 10-08 起的主线会话都在这里）；
  `C:\Users\mfYang\.workbuddy-ai\` = 5.7.7 起的新目录。安装目录 `E:\workbuddy\WorkBuddyAI` 与数据无关。
- 升级到 5.7.7 时本地迁移失效（迁移脚本只认本机为空的 `%APPDATA%\WorkBuddy\codebuddy-sessions.vscdb`）
  → 新库为空 → **侧边栏会话列表清空**（`migration-*.log` 里 10 步全 `skipped` 但状态写 `SUCCESS`）。
  排查入口：`logs/AppStartup.log`（找 `reason=update`）、`logs/migration/`、`workbuddy.db` 的 `sessions` 表、
  `user-*/sidebar-list-snapshot.json`（`items:[]` 即列表为空）。
- **规矩：升级前手动备份整个 `C:\Users\mfYang\.workbuddy\`**；
  排查"任务/会话丢失"时先看旧目录的 `workbuddy.db` + `projects/*/**.jsonl`，会话内容与配图
  （`clipboard-images/`）通常都还在，不必重做。
