# 项目长期记忆

## 仓库布局（容易找错，先读这条）

工作区根目录 `Mini_Nodepad` 是 **RuoYi 风格的 Java 后端**（`pom.xml` / `.mvn` / `src`）；
**前端工程在兄弟目录**：

- 前端：`D:\ruoyi\RuoYi-Vue-master\mini-notebooklm-web`（Vue3 + TS + Vite + Tailwind v4）
- 后端：`D:\ruoyi\RuoYi-Vue-master\Mini_Nodepad`

所有辅助脚本 `_*.py`（`_tsc.py` / `_build.py` / `_check_css.py` / `_start_dev.py`）
都在前端目录里。用 Glob 找前端文件时不要在 `Mini_Nodepad` 里找。

## 前端技术栈与约定

- Vue 3.5 + TS 5.9 + Vite 8 + Pinia 4 + reka-ui + lucide-vue-next。
- **TailwindCSS v4，CSS-first，没有 `tailwind.config.js`**：
  `@theme inline` 写在 `src/styles/index.css`，`@tailwindcss/vite` 插件在 `vite.config.ts`。
  用户偶尔会按 v3 的习惯说"改 tailwind.config.js"，等价位置就是 `index.css` 的 `@theme inline`。
- 三层 CSS：`primitives.css`（`--x-*` 原语，唯一实现三份规范的地方）
  → `tokens.css`（语义层，变量名不可改）→ `index.css`（`@theme inline` + 组件类）。
  组件只消费语义类，换风格只改原语层。
- 三正交维度：`data-theme`(light/dark) × `data-style`(apple/swiss/editorial) × `data-accent`(7 色)。
- 强调色卡只对 `apple` 开放（Swiss 只用黑白红，Editorial 严禁彩色）。

## 六条反复踩过的坑

1. **Tailwind v4 的 `font-serif` 默认值是 ui-serif/Georgia**，只改原语层没用 ——
   必须在 `@theme inline` 加 `--font-serif: var(--x-stack-serif)`，
   否则模板里写的 `class="font-serif"` 永远落不到思源宋体。
2. **`@theme inline` 只认 `--color-*` 键名**。写了 `--color-ink-invert` 只能用
   `text-ink-invert`；别处写的 `text-fg-invert` 是**不存在的类**，不生成任何 CSS，
   静默失效（曾导致思维导图根节点整块纯黑无字）。要用就补同值别名
   `--color-fg-invert: var(--fg-invert)`。
3. **压缩器会把同一个选择器拆成多个规则块**。核对 CSS 产物时，
   同名选择器要**拼接**而不是 dict 赋值，否则后写的碎块盖掉前面整段，刷出一片假 MISS。
   同理 `#ff0000`→`red`、`#ffffff`→`#fff`、`150ms`→`.15s`、选择器引号会被去掉。
4. **同一文件的多个 Edit 并行下发会互相覆盖**，只有最后一个生效，但工具都报 success。
   同文件必须串行改，改完回读 `dist` / `dist-smoke` 产物核对实际值
   （vue-tsc 与 vite build 覆盖不到 CSS 变量值）。
5. **可选边框不要用 `border-bottom: var(--x, none)` 这种简写开关** ——
   变量为 none 时把该边框整个杀掉，误杀其余风格（曾让思维导图所有节点盒子没底边，
   相邻叶节点视觉上连成"空盒"）。要用的写法：完整 border 承担宽度/样式，
   可选的一边只用 `border-bottom-color` 长手覆写颜色。
6. **`:root` 定义的 `--x-*` 会沿继承链渗入 `[data-style]` 段**，
   `var()` 的 fallback 只在【整条继承链都没定义】时才生效
   （曾让 Editorial 叶节点继承到 `:root` 的强调色底，凭空长出灰底）。
   规矩：`--x-mm-*` 等风格变量，每个 `[data-style]` 段显式写全自己需要的值。

## 思维导图沉浸态（设计文档第 16 章）

- **放大态画布零图形库**（不引 D3/markmap —— 图形库会把节点画成 SVG `<text>`，
  无法复用 `.mm-node` 三风格原语）。改用纯 Vue 模板 + CSS `transform`：
  节点是真实 `<button>`、连线是 SVG `<path>`（三次贝塞尔）、缩放只改 world 容器 transform。
- **连线线宽不随缩放变粗**靠 `vector-effect: non-scaling-stroke`，否则放大时连成粗带。
- 布局算法抽成纯函数 `utils/mindmap-layout.ts`（tidy-tree：后序行分配 + 同层左边缘对齐列），
  可在 Node 直接做几何单测。
- **叶节点 = 提问入口**，点后右栏切换 `AiCopilot`（复用同一条会话）。右栏视图状态在
  `stores/layout.ts` 的 `rightPanelView`（`'studio' | 'copilot'`）。"能否提问"四条闸
  抽在 `composables/useAskGate.ts`，中栏与 Copilot 共用。
- **画布组件模板里零 `data-style` 判断**，三风格差异全在 `--x-mm-*` 原语。
- 缩小态（右栏抽屉）是 VS Code 级竖排缩进树（`MindmapNode.vue`）。

## 常备工具：`_cdp_mindmap.mjs`

思维导图专用 CDP 探针（与 `_cdp_probe.mjs` 同路线）。核心是 `CANVAS_PROBE` 体检：
节点/连线计数、根节点形态、画布底纹、连线形态、视口 zoom、右栏是否被 Copilot 接管。
流程：找历史上思维导图 → 最大化 → 量初始 → 点根 → 点分支 → 滚轮放大 → 点叶发问 → 逐风格截图。

## 常备工具：`_cdp_theme_shot.mjs`

三风格「创作入口 / 来源行 / 添加来源」取证探针。自己登录（demo/demo12345，走页面
`/api/v1/auth/login`，**authorization 头是裸 JWT 不带 Bearer**）→ 遍历三风格 →
逐槽位量 display/字号/字体/字重/边框 + 截图 `_shot_<style>_{full,left,right}.png`
+ `_theme_report.json`。改三栏视觉后必跑。

## dev server

`_start_dev.py` 以 detached 方式起 Vite，`http://127.0.0.1:5173`，
pid 记在 `_dev.pid`，日志在 `_dev.log`。探测存活直接 `urlopen('/')`。

⚠️ 2026-10-10 反复实测：**从工具侧起的前端没有一个能活过回合结束** ——
bash `&`、`run_in_background`、`subprocess.DETACHED_PROCESS`（`_start_dev.py`）
全都被回收（后端 8082 反而能活）。所以**前端必须由用户在自己的窗口起**，
用户报「用不了」时先按这个前提排查，别浪费时间在工具侧反复重启。

→ 给用户的三个双击脚本（全在前端目录，纯 ASCII，防 cmd 代码页乱码）：
  - `start-dev.bat`      只起前端。自探测 node（PATH → `E:\nodejs` → 托管版），
                         清代理变量，探测 5173 占用，8082 没起时打 WARN。
                         直接 `"<node>" node_modules\vite\bin\vite.js`，**不走 npm**
                         （npm 未必在用户 PATH 里）。
  - `start-backend.bat`  起后端 8082（`JAVA_HOME=D:\java`、MAVEN 3.9.4、
                         `spring-boot:run`，先探测端口占用再提示）。
  - `start-all.bat`      开两个窗口，一次起齐。

后端临时启动（工具侧）：`_run8082.py`（复用 `_mvn.mvn`，8082，日志 `_back.log`）。

⚠️ 本会话 Bash 环境自带 `http_proxy=127.0.0.1:2650`：python urllib 会走代理、
Node fetch 不走 —— 同一个地址两者结论可能相反。诊断连通性用 Node fetch，
或 python 加 `env -u http_proxy`。

## ⛔ 绝对不能改的一处（改回去会整页白屏）

`src/App.vue` 的路由出口是：

```html
<RouterView v-slot="{ Component, route }">
  <Transition name="route">
    <component :is="Component" v-if="Component" :key="route.path" />
    <div v-else>正在加载页面…</div>
  </Transition>
</RouterView>
```

**这个 `<Transition>` 永远不能加 `mode="out-in"`。**
Vue 3.5 + vue-router 5 下，`out-in` 的"离开过渡完成后再插回新组件"会拿到
`undefined` 的插槽值，渲染成一个注释占位符并**永久停住** → 点「书库」/「管理来源」
整页纯白、**控制台零报错**、刷新才好。加 `:key` 无效（已实测）。
因为不能带 out-in，过渡期间新旧两页会同时在 DOM 里，所以 `.route-leave-active`
里用 `position: absolute` 把离开态脱离文档流。详见设计文档第 14 章。

**配套铁律（2026-10-10 新增）：views/* 的模板根元素在组件存活期间绝不允许被内部
v-if/v-else 换掉。** App.vue 的 `<Transition>` 在路由离开时只认【第一次挂载时】的
根 el：NotebookView 曾把 `v-if="ready"` 直接写在根上（加载 div ↔ WorkspaceLayout
内部换根），离开过渡作用在失效 el 上，真正显示的页面**永远不被卸载** ——
点「返回书库」后新旧两页同时存在、URL 已是 /books 但界面卡住，且**零报错**。
需要二态就套一层 `<div class="route-shell">` 稳定外壳（NotebookView 已改）。
取证工具：`_cdp_route_repro.mjs`（真鼠标点「书库」→ 120ms/3s 两次 DOM 体检）。

## 常备工具：`_cdp_probe.mjs`（真浏览器验证）

要用**真 Chrome** 复现问题（白屏、渲染、动效）时用它 —— 本机没有 `agent-browser` CLI，
但 Chrome 在 `C:\Program Files\Google\Chrome\Application\chrome.exe`，且 Node 22 自带原生
`WebSocket`，所以直接 headless Chrome + CDP，零依赖。
测试账号 `demo / demo12345`；token 存 localStorage 的 `mnl.token`。
用法：设 `MNL_TOKEN` / `MNL_BOOK` / `MNL_OUT` 后 `node _cdp_probe.mjs`。
它会跑 7 步导航并输出 DOM 体检 + 截图。

取证要点：**白屏 + 控制台干净 = 去看 DOM 有没有元素**，别去猜 JS。
⚠️ `document.body.innerText` 会被 DOM 注释污染，硬指标要取真实元素的文本。

## 写注释的硬规矩

- CSS 注释正文里**不要出现 `*/`**（会提前终止块注释）。
- HTML 注释正文里**不要出现连续两个短横线**（会把注释当场闭合，
  后半段变成页面上的可见文字）。这两条都实际踩过。

## ⚠️ 浮层 vs 面板：Editorial 透明性的分界（2026-10-10 定稿）

Editorial 把 `--x-card`/`--x-panel` 设为 transparent 是**面板**（栏/岛）的纸张感；
**浮层**（弹窗/菜单/气泡/提示条/闪卡面）必须实心，否则与底下内容叠字
（曾致生成配置弹窗、外观浮层、书库菜单全部看不清，闪卡正反面文字互相穿透）。

规矩：
- 所有浮层组件统一挂 **`.overlay-surface`** 类（AppDialog 是所有弹窗的底座，
  挂它一处即覆盖全部 Dialog）。Editorial 覆写段写在 index.css **顶层**（脱离
  @layer 才能盖过 utilities 的 border-line/shadow-e3）：象牙白 #fcfaf2 + 颗粒
  纸纹 + 铅笔灰线 rgb(42 43 45/.15) + 硬边错落纸影（0 模糊，不算"弥散阴影"）
  + 2px 圆角；暗色 #26241f。变量：`--x-overlay-paper/line/edge/grain`。
- **新增浮层必须挂它**（`_check_css.py` 有挂类清单断言）。
- `--x-card`/`--x-panel` 的透明是设计意图，反向断言锁死，别顺手改实。
- Apple 书库网格卡 = MD3 outlined：`.book-card` + `--x-outline-variant`
  （浅 #c4c7c5 / 暗 #444746），hover 边框不动只沉底色（`--x-hover`）。
  取证探针：`_cdp_overlay_shot.mjs`。

## 面板开合与右侧轨道（设计文档第 15 章）- **左栏开合入口只有中栏顶栏的 `PanelToggle`**。左栏栏头的 `←`、左边缘的悬浮把手
  都已删除；**别再加回来** —— 三个入口管同一件事就是用户说的"有两个返回按钮"。
- **右侧抽屉必须贴死视口右缘**（`rightPanelWidth`：collapsed→`--rail-width`，
  其余→`--panel-width`）。试过"轨道常驻 + 抽屉让位到轨道左侧（calc(rail+panel)）"，
  用户一眼否掉（"没展开在右边、展开在左边"），已回滚。右侧开合 =
  收起态的轨道 + 展开态抽屉头的 `→`。
- **Swiss 开关闲置态是实心墨块**（`background-color: var(--fg)`，暗色自动反相），
  **不要画三条横线（汉堡）**：用户点名要去掉。
- 右侧 `<aside>` **不能加 `overflow-hidden`**，否则轨道 hover 长不出去；
  裁切只放在内层"只包抽屉"的那个窗口上。
- 三套开关形态全靠 `[data-style]` 在 CSS 里切换，**组件模板零风格判断**
  （与 `.icon-apple` / `.glyph-plain` 同一套路）。

## 常备工具：`_cdp_style_shot.mjs` / `_cdp_md3_shot.mjs` / `_png_lines.py`

验证"hover 才出现"的样式必须用前者 —— CSS `:hover` 不是改 class 能触发的，
它用 CDP `Input.dispatchMouseEvent` 移动**真指针**，逐风格量尺寸 + 截图 + 局部放大。
`_cdp_md3_shot.mjs` 是 MD3 悬浮岛专用探针：apple×明暗全量量测（画布 pad/gap、岛圆角、
grid 工具箱、tonal 色值、真指针 hover Tooltip）+ **Swiss/Editorial 隔离守卫**
（圆角必须 0、entries 必须 flex）——动 apple 段后跑它，防止污染另外两套。
后者 `_png_lines.py` 用最小 PNG 解码器扫描截图里的长直线，把用户口中的"那条线"定位到
像素坐标与颜色（用户说"某条线"时**先量再问，别猜**；配合编号示意图让他点，一次就问准）。

## MD3 悬浮岛（apple 主题，2026-10-10 重构）

- apple = MD3：画布 `#F0F4F9`（暗 `#131314`）+ 三座白岛（暗 `#1E1F20`）`24px` 圆角 +
  `ring-black/5`；右栏 `grid-cols-2` h-12 胶囊工具箱 + tonal 五色
  （导图 blue/测验 green/闪卡 amber/报告 purple/演示 rose，暗色 -900/30 反转）；
  左栏类型图标**绝对中性灰**（浅 `#f1f3f4` / 暗 `#2A2B2D`），彩色只给右栏；
  卡片副标题 = hover 深色 Tooltip（必须落在卡片横向范围内，滚动容器会裁越界浮层）。
- 图标：`unplugin-icons` + `@iconify-json/material-symbols`（Rounded 后缀图标名），
  编译期 SVG、尺寸靠 CSS `font-size` 下发（不是 `:size`）。
- **⚠️ 风格几何变量（`--x-island-pad/gap` 等）一律放 `[data-style]` 基础段**，
  明暗段只放颜色 —— 写进 light 段会让暗色下画布 padding 归零、三岛贴死
  （量测数据才能逮到，截图看不出来）；`_check_css.py` 已有反向断言。
- 亮暗判断：`[data-style=apple]` 段被压缩器拆块时，`_check_css.py` 的 blocks 按
  `[data-style=x][data-theme=y]` 聚合拼接，不会漏。

## 设计文档

`docs/多主题架构设计-风格与主题色.md`（第 13 章：字体 CDN 化 / Swiss 硬约束复归；
第 14 章：路由过渡白屏的根因与修复；第 15 章：面板开关三形态与右侧轨道；
第 16 章：思维导图沉浸态画布 / 极简面包屑 / AI Copilot 右栏）。
改风格或动三栏骨架前先读它。

## 文档组织约定（2026-10-11 盘点定下）

docs 分两层，**别再让它长草**：

- 两个 docs 目录（后端 `Mini_Nodepad/docs`、前端 `mini-notebooklm-web/docs`）
  各自只留**现行**文档，各有一份 `文档索引.md` 登记「待办 / 现行 / 归档」三张表。
- 已完成或已失效的文档移到同目录 `归档/` 子目录（保留可追溯，但不再当依据引用）。
- 已归档（10-11）：`审核-历史消息接口实现.md`、`审核-工件动态命名改动清单.md`、
  `审核-动态命名核心Diff.md`、白皮书 v3.0、`主对话框，优化方向.md`（后端侧）；
  `后端对话流修复清单.md`、`后端接口实现清单-来源管理.md`、`后端修改建议清单.md`、
  `Studio来源管理与生成面板改造方案.md`、`Studio工件渲染与配置面板.md`（前端侧）。
- **唯一真·待办**：`Mini_Nodepad/docs/后端待办清单.md`（引用原文 / 重新生成 / 赞踩反馈，
  10-11 复核三项端点均不存在）。
- 新增文档要顺手在 `文档索引.md` 里登记一行。

## ⚠️ WorkBuddy 自身的数据目录（2026-10-10 升级事故）

本机有**两个** WorkBuddy 数据目录并存，别搞混：

- `C:\Users\mfYang\.workbuddy\` —— **5.6.2 及以前**的数据目录，
  主线会话 `0b7d75f0-*`（本项目从 10-08 起的全部对话）都在这里，46MB / 6811 条。
- `C:\Users\mfYang\.workbuddy-ai\` —— **5.7.7 起**的新数据目录，
  里面只有升级后新建的会话。

安装目录是 `E:\workbuddy\WorkBuddyAI`（与数据目录无关，**别去那儿找数据**）。

**升级到 5.7.7 时本地迁移会失效**：迁移脚本只认
`%APPDATA%\WorkBuddy\codebuddy-sessions.vscdb`（本机是空的），
于是 `migration-YYYYMMDD.log` 里 10 个步骤全部 `skipped` 但状态写 `SUCCESS`，
新版在 `.workbuddy-ai` 建空库 → **侧边栏会话列表清空**。
排查入口：`logs/AppStartup.log`（找 `reason=update`）、
`logs/migration/`、`workbuddy.db` 的 `sessions` 表、
`user-*/sidebar-list-snapshot.json`（`items:[]` 即列表为空）。

**规矩：升级前手动备份整个 `C:\Users\mfYang\.workbuddy\` 目录。**
排查"任务/会话丢失"时，先看旧目录的 `workbuddy.db` + `projects/*/**.jsonl`，
会话内容与配图（`clipboard-images/`）通常都还在，不必重做。

## ⚠️ 8082 端口：工具侧起的后端会挡住用户在 IDEA 里启动

后端 dev 端口 = **8082**（`src/main/resources/application-dev.yaml` 的 `server.port`，
默认 profile 就是 `dev`），前端 vite proxy 也指向 `http://localhost:8082`。
配置文件后缀是 **`.yaml` 不是 `.yml`**（用 `application*.yml` 去 grep 会一无所获）。

- 用 `_run8082.py` 在工具侧起实例做 E2E 验证后，**收尾必须 `taskkill` 掉**，
  否则用户回 IDEA 启动会报
  `APPLICATION FAILED TO START — Web server failed to start. Port 8082 was already in use`。
- 判断 8082 被谁占：`netstat -ano | grep ":8082.*LISTENING"` 拿 PID →
  `tasklist /FI "IMAGENAME eq java.exe"` 对内存占用（Spring Boot 实例通常 500MB+，
  IDEA 自身的 java 进程偏小），别误杀 IDE。
- `_run8082.py` 会被**重复调用**时，第二个实例起不来但会静默写 `_back.log`
  （内容是"端口占用"的 BUILD FAILURE），容易被误读成编译错误 —— 它其实不是。

## 双路并发 + 两段流式（2026-10-12 落地）—— 三条铁律

1. **前端「停顿」判据只能看正文有没有增长，不能看 phase。**
   `phases` 是累积数组（某 phase 一旦到达永不移除）；且实测真正让用户干等 10~25s 的是
   **Pass2 的模型首字延迟**（那时 phase 已是 `thinking`），**不是**"等联网"——
   实测 Pass1 收尾与联网返回只相差几十毫秒。
   正确写法：`streaming && hasContent && Date.now() - lastGrowAt > 1200`（400ms tick 驱动重算）。
2. **测试里不要写绝对毫秒门限。** 云模型（doubao-seed-2-0-pro）首字延迟 6.5~12.7s 波动，
   会支配一切时间指标。要用相对判定：首字早于段间停顿点、且不到整轮耗时的一半。
3. **引用列表与检索开关必须同源同判。** 低于余弦门槛（`rag.dual-path.pass1-cosine-gate: 0.28`）
   的本地片段既不能进 Pass1、也不能进引用列表，否则用户会看到与问题无关的「笔记来源」；
   实现上用同一个 `localAdopted` 判据派生两者。

## 日志与配置（2026-10-12）
- `_mvn.py` 的 stdout 解码必须是 `utf-8`（原为 `mbcs` → 日志中文在落盘前就不可逆乱码）；
  `application.yaml` 另加 `logging.charset.console/file: UTF-8`。改完日志中文可直接 grep。
- `web-search.pre-search-timeout-ms: 8000` 偏紧（Aliyun Rerank 偶发 19.2s）→ 假降级，
  建议提到 15000~20000（仍低于 `rag.dual-path.web-budget-ms: 30000`）。
- 前端 DOM 目检：页面会加载历史消息，断言必须收敛到**最后一条 `.cm-ans`**
  （否则把历史气泡和引用 chip 一起数进来，实测 ansCount=3 / local=15 全是假的）。
- 可直接复用的测试资产：`_e2e_dualpath_suite.mjs`(13)、`_e2e_dualpath_degrade.mjs`(2)、
  `_run8082_degraded.py`、`_guard_probe.java`(24)、`_probe_dualpath_ui.mjs`(三主题)。

## ⚠️ 会话工作目录陷阱（2026-10-13 发现，务必先读）

**本工程的会话工作目录不一定在 `Mini_Nodepad`。** 2026-10-12 的 Graphiti / Phase 4
全部工作，会话 CWD 实际是 `D:\7-Zip\springai_tes`（Spring AI 学习工程），
于是：
- 自动注入 + 自动追加的**工作记忆落在了 springai_tes**（`.workbuddy/memory/2026-10-12.md`
  与 `MEMORY.md` 里混入了本项目的 Graphiti 内容），本文件（Mini_Nodepad 的 MEMORY.md）
  因此长期缺这部分结论；
- 但**读写工程文件一直用的是绝对路径**，所以 `docs/` 与 `src/` 没出过错。
- ⇒ 规则：动手前先确认 CWD；发现 CWD 不是 `Mini_Nodepad` 时，本项目记忆用绝对路径写，
  并提醒用户切换工作目录。别在两个工程的 `.workbuddy/memory` 之间产生重复日志。

## Phase 4：Graphiti 记忆微服务集成（定稿在案，未写业务代码）

**定稿文档**：`Mini_Nodepad/docs/Phase4-Graphiti记忆微服务集成-架构设计方案-v3-Final.md`
（1607 行 / 140KB，rev.2，含 §0.1~§0.7 决策索引与 6 条铁律、附录 A 契约速查、
附录 B 实现陷阱 33 条）。

**服务端地址**：`http://192.168.88.130:8088`（VM，容器 `memory_graphiti` + `neo4j`）。

**Java 侧契约（B0 要实现）**：
- `GraphitiMessage{ content, uuid(★一律 null), name(★确认锚点), roleType(必填),
  role(⚠️必填字段/可为 null), timestamp(⚠️OffsetDateTime, 必须显式传), sourceDescription }`
- `POST /messages {group_id, messages[]}` → 202 异步；**每条 message 独立成 episode**。
- `POST /search {group_ids[], query, max_facts}` → **不过滤 invalid_at、返回体无 group_id**。
- `GET /episodes/{group_id}?last_n=N` → **`last_n` 必填**、纯时间倒序
  （排序键 = `created_at` 落库时刻，**不是 `valid_at`**）、**无 offset/游标** → 查不到老 episode；
  时间格式 `...Z`（`/search` 是 `+00:00`，`OffsetDateTime` 两者都能吃）；
  加 `&query=React` 这类**未知参数被静默忽略**（返回逐字节一致，别指望它做过滤）。
- ★ `/episodes` 返回的是 **EpisodicNode 原始序列化（非 DTO）**：`uuid` / `name` /
  `source_description` / `created_at` / `valid_at` / `content` / `group_id` / `entity_edges`。
- ★ `POST /messages` 的 `messages[]` **每条独立成一个 episode**
  （实读 `routers/ingest.py`：`for m in messages: queue.put(partial(add_messages_task, m))`），
  各自带 uuid 与 `reference_time = m.timestamp`。三条推论：
  ① 双时间戳裁决粒度 = **单条消息**；② `timestamp` **必须显式传**
  （默认 `utc_now()` 会把历史对话盖成处理时刻，裁决失效）；
  ③ 一次 POST 带 N 条 = N 次**串行** `add_episode`（单协程 worker，每条 20~45s）
  → **一批别超过 3 轮**。
- ★ `Message.name` 是「**落库确认锚点**」的唯一可用载体（默认空串、完全空闲）：
  `/messages` 的 **202 响应不含服务端生成的 uuid**，而 `/episodes` 又无过滤/无游标
  → 服务端 uuid **事前不可知、事后难反查** → 确认只能按 `name` 比对，
  并在命中时顺手把 uuid 回填本地（零额外请求）；`source_description` 专心写人话式来源说明。

**三条最容易踩死的实测结论**：
1. ★★ `add_episode` 的 `uuid` 非 null 语义 = **update 已存在 episode**，查不到直接
   `NodeNotFoundError` → **job 静默失败、零写入，而 HTTP 已返回 202**。新建必须 `uuid=null`。
2. ★★ episode 层**没有任何向量能力**（枚举只有 bm25、`EpisodicNode` 无 embedding、
   Neo4j 无 VECTOR 索引）→ 用 Graphiti 做"语义原话回溯"必死。
3. ★ 中文 BM25 **能命中**但打分不可分（真命中 0.045 vs 噪音 0.035）→ 不存在可用阈值。

**六条开发铁律**（详见 §0.7）：DB CAS 占位（废 Redis setnx，CAS 必须在 POST 前）／
异步对账防死锁（`ConfirmJob` + `last_n` 按窗口量纲算）／429·403 走长退避不做 4xx 毒消息／
背压只看 MQ 物理积压 + ReplayJob 闭环补投／召回 `limit×4` 宽拉取严过滤再截断／
提示词双通道（`userGlobalMemory` + `episodicMemory`）物理隔离禁合并排序。

**实施顺序**：B0 契约层 → B1 写入链 → B2 图谱质量 → B3 召回注入 → B4 影子验收。

## 基础设施：Graphiti 微服务 / VM / Docker API（2026-10-13 从串台记忆中归位）

> 来源：此前误落在 `D:\7-Zip\springai_tes\.workbuddy\memory\MEMORY.md` 的段落，
> 2026-10-13 迁回本项目。以下均为**实机实测**结论，不是推测。

### Graphiti 服务契约细则（VM 192.168.88.130:8088，v2 镜像已验收）
- 写：`POST /messages {group_id, messages:[{content, role_type, role, source_description?}]}`；
  检索：`POST /search {query, group_ids[], max_facts}`（中文放 body）。
  **无 POST /episodes、无 user_id、无 source 字段**；`messages[]` 缺失 = 422；
  `Message.role` **无 default = 必填**（可 null 但字段必须出现）。
- ⚠️ `/search` 的 `group_ids` 可选可 null：**漏传或写错字段名 `group_id` = 跨租户全局检索且零报错**；
  返回的 fact **不含 group_id** → 服务端应对空 group_ids 返回 400。
- ⚠️ `POST /clear` 无参数 = **整图清空**；按组清理用 `DELETE /group/{group_id}`。
- ⚠️ `/search` 与 `/get-memory` **都不过滤已作废事实** → 客户端必须 `invalid_at is None` 过滤。
- ⚠️ 边标签由模型自由生成，**Cypher 不能硬编码关系名**。
- `FactResult` 字段：`uuid, name, fact, valid_at?, invalid_at?, created_at(必填), expired_at?,
  source_node_uuid, target_node_uuid, episodes[]`；**无 group_id**；
  时间序列化 `astimezone(UTC).isoformat()`（微秒+offset）→ Java 侧**必须 `OffsetDateTime`**
  （全局 Jackson 把它钉成无 offset 格式，必然解析失败）。另有 `GET /entity-edge/{uuid}`、
  `DELETE /episode/{uuid}`。
- ★ `/search` 路由**丢弃 episodes** 只返回 edges；真正的全量 `search_()` 服务端未暴露。
- ★ `fact.episodes[]`（episode uuid 列表）+ `GET /episodes/{group_id}` 反查原话
  = **记忆溯源**（"这条偏好从哪句话提炼"），零新增设施；但只能溯源已抽成事实的对话。
- 部署：compose 在 `/root/graphiti-memory`；`ingest.py` 实为 `routers/ingest.py`
  （COPY 错路径会静默失效）；容器内无 nc，探测用 Python socket；neo4j 数据 = bind mount。
- 改 compose 用 `docker-compose.override.yml`（⚠️ ports/volumes 是列表追加，
  **override 里绝不能写 ports**）。
- ⚠️ 换 embedding 模型**必须清库**（v3/v4 同 1024 维不报错，但向量空间不同 → 相似度静默失真）。
- ★ 模型分水岭：`qwen-plus` 才有教科书级双时间戳裁决；`qwen-turbo` 会降级甚至自相矛盾。

### Graphiti 服务端 4 个根因（v2 镜像已修）
① embedder 未接线；② 默认 `OpenAIClient` 走 OpenAI Responses API（第三方端点不支持）
→ 必须换 `OpenAIGenericClient`；③ FastAPI `finally: client.close()` 提前关连接；
④ ingest worker 只 `except CancelledError`，异常静默杀死循环。
- ★ 改环境变量无用，要读代码（pydantic `extra='ignore'` 静默丢 `EMBEDDING_MODEL`）；
  判定配置是否生效**只看服务实际生效的 config 对象**。
- ★ 换 LLM client 必须**同时**赋值 `client.llm_client` 与 `client.clients.llm_client`
  （`add_episode` 读后者）。
- DashScope 支持原生严格 `json_schema`（不兼容的只是 Responses 端点）；
  `json_object` 会让模型把 schema 本身当答案。

### Docker Engine API over TCP（VM 运维）
- `http://192.168.88.130:2375`（**无 TLS 无鉴权 = root 权限**）。列表 `/containers/json?all=1`、
  `/logs?stdout=1&stderr=1&tail=N`、`/start`、`/archive?path=`、`DELETE /images/{id}`。
- **日志是 mux 帧**，必须读原始 bytes 再切帧。exec 到已停止容器返回 409。
- 传文件：`sh -c "echo '<base64>' | base64 -d > /tmp/x.py"`；别用带分号的 `python -c`。
- ★ 远程构建：`POST /build?t=<tag>`，body=tar，结果看 aux.ID / error；
  层会被宿主机 `compose --build` 命中 CACHED。
- ⚠️ 坏代理 `http_proxy=127.0.0.1:13514`：curl 用 `--noproxy '*'`，urllib 用 `ProxyHandler({})`。
- ★ **非 root 用户 + PATH ≠ 可执行**：PATH 含该目录但非 root 跑 `bash -c` 报 127
  → POSIX 目录穿越权限（uv 只在 `/root/.local/bin`，而 `/root` 是 `drwx------`）。
  迷惑点：镜像自带 CMD 能跑通（runc 以 root 解析并 fexecve 启动 PID 1）。
  启动脚本一律绝对路径，优先 venv 内可执行文件（`/app/.venv/bin/uvicorn`）；
  restart + 脚本早退 = 无限重启循环，日志像「卡在等依赖」。
- ★ **基底 `USER` 非 root 时 `COPY` + `RUN chmod` 必失败**（Operation not permitted）。
  修法：`CMD ["/bin/bash","/app/script.sh"]` 删掉 chmod，或 `COPY --chmod=0755`。
  反推：声称 Dockerfile 构建成功、里面却有 `RUN chmod` 的，多半是 `docker commit` 产物。
- ★ **启动竞速**：graphiti 比 neo4j 只晚 1~2s → `build_indices_and_constraints` 抛
  ServiceUnavailable → exit 3；`RestartPolicy=no` 时对外只表现为 **502**。
  解法：入口脚本 wait-for-neo4j + 两服务都 `restart: unless-stopped`。
  实测冷启动 110.9s 就绪、RestartCount=0。

### VM 网络 / 暴露面
- **NAT 不是桥接**：宿主机 192.168.88.1 = VMware VMnet8；虚机 .130；
  ARP 实测仅这两台 → 外部物理网络无法主动入站。
- 34 个端口绑 `0.0.0.0`（3306 / 6379 / 6380,8001 / 6333,6334 / 7474,7687 弱口令
  password123 / 5672,15672 / 3001 / 8000 / 8088 / 18060）。
- ★ 重启 dockerd 会掉 6 个 `restart=no` 容器
  （qdrant / redis-stack / rabbitmq-server / postgres-db / rabbitmq / youthful_archimedes）。
- 宿主机 CentOS 7（已 EOL）、Docker 26.1.4、2 vCPU / 2.9GB。
- ★ **firewalld 挡不住 Docker 发布端口**（走 PREROUTING DNAT → FORWARD/DOCKER-USER），
  只能拦 INPUT（如 2375）。限制容器端口用 `iptables -A DOCKER-USER`
  或把 ports 写成 `192.168.88.130:3306:3306`。
- ★★ **DOCKER-USER 白名单的经典自伤**（实际踩爆）：
  `-i ens33 ! -s 192.168.88.0/24 -j DROP` 会切断**所有容器出网**（回包源是公网 IP，被同一条规则 DROP）。
  诊断特征：容器**出网 TCP 全 timeout，但 DNS 正常、容器间与宿主机→容器正常**，三者同时成立
  = FORWARD/DOCKER-USER 被拦。
  正确写法必须限连接状态：
  `-I DOCKER-USER 1 -i ens33 -m conntrack --ctstate NEW ! -s 192.168.88.0/24 -j DROP`。
  复位：`iptables -F DOCKER-USER && iptables -A DOCKER-USER -j RETURN`。

### 待办（基础设施侧，未收口）
- 🔴 Docker 2375 无 TLS 无鉴权（等价 root），尚未收口。
- 🔴 `/search` 的 `group_ids` 可空漏传 = 跨租户检索（服务端未加 400 校验）。
- 🟠 `semantic.scan.queue` 未声明 DLX；🟡 API key 明文在 compose；🟡 CentOS 7 已 EOL。
