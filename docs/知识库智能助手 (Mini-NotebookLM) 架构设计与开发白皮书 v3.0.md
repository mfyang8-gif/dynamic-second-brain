

# 知识库智能助手 (Mini-NotebookLM) 架构设计与开发白皮书 v3.0

## 1. 项目当前状态与里程碑

- **✅ 阶段一：核心检索与记忆封装 (已完成)**
  - 基于 `sessionId` 的记忆物理隔离与基于 `bookId` 的 Qdrant 检索域解耦。
- **✅ 阶段二：流式对话框开发 (已完成)**
  - 完成了 `ChatStreamService` 和 SSE 接口开发，支持 `status`, `message`, `error`, `done` 等标准事件通信。
  - 完成了前端对话框的 UI 渲染。
- **🚀 阶段三：UX 进阶与 Studio 衍生功能 (当前开发重点)**
  - 动态推荐问题生成（智能追问）。
  - Studio 侧边栏异步生成工具箱（测验、闪卡、导图、报告）。
  - 全局状态机、前端内联交互与交互式组件嵌套。

## 2. 核心架构原则 (Core Design Principles)

- **避免过度设计**：业务编排层（如 `ChatStreamService`）采用单一具体类；多策略派发流程（如 Studio 生成）采用泛型与策略模式。
- **防阻塞异步设计**：所有 LLM 流式输出与后台生成任务均由 Java 21 虚拟线程 (Virtual Threads) 接管，确保底层 Tomcat 的 HTTP 工作线程毫秒级释放。
- **单表多态存储**：针对不同结构的衍生工件（Artifacts），采用单一表结构配合 JSON payload 与类型鉴别器 (Discriminator) 进行存储。
- **全局单例长连接**：拒绝多路杂乱连接，采用全局统一的 SSE 通道进行任务完成通知。

## 3. 数据库与实体层设计

### 3.1 衍生工件状态机 (Studio Artifact Domain)

用于支持耗时生成任务和多态数据展示：

- **`studio_artifact` 表**：
  - `id`: UUID
  - `book_id`: 关联知识库
  - `type`: 鉴别器字段 (枚举：`QUIZ`, `FLASHCARD`, `MINDMAP`, `REPORT`, `PRESENTATION`)
  - `status`: 异步状态机 (枚举：`PENDING`, `PROCESSING`, `SUCCESS`, `FAILED`)
  - `payload`: JSON 类型。依据 `type` 存储不同的结构化数据 (如题库、卡片正反面)。
- **多态实体映射**：通过 Jackson 的 `@JsonTypeInfo` 和 `@JsonSubTypes` 依据 `type` 字段动态反序列化为对应的 Java Record/POJO。

## 4. UX 进阶功能：智能追问 (Suggested Follow-up Questions)

根据产品需求，当主对话流结束时，需要在输入框上方展示 3 个动态推荐的后续问题（Pill Buttons）。

### 4.1 架构实现方案 (Piggybacking on SSE)

为了保证极致的首字响应速度，绝不能让主回答等待推荐问题：

1. **触发时机**：在 `ChatStreamService` 的 `.onComplete()` 回调中，主回答已经完整生成。
2. **异步生成**：开启一个并行的虚拟线程任务，将“用户的上一个问题 + 刚生成的回答”作为 Context，调用低延迟小模型，返回包含 3 个字符串的 JSON 数组。
3. **事件扩展**：向现有的对话 SSE 协议中新增事件类型：
   - `event: suggestions`
   - `data: ["在商业决策中如何利用阳谋?", "有哪些防范群体盲从的措施?"]`
4. **前端渲染**：前端监听到事件后，渲染为可点击标签。点击后直接填入输入框并触发新一轮请求。

## 5. Studio 衍生功能模块 (策略模式架构)

摒弃“大胖子 DTO” 反模式，采用面向对象的继承体系与独立端点。

### 5.1 DTO 继承体系与基础参数

- **`BaseStudioRequest` (基类)**: 包含所有组件共享的参数：
  - `bookId` (必填): 知识库 ID
  - `sourcePageIds` (可选): 限定生成的来源文档范围
  - `topic` (可选): 用户自定义侧重主题
  - `language` (可选): 默认 zh-CN

### 5.2 Studio 核心组件 Schema 设计 (扩展参数)

#### A. 测验 (Quiz)

- `countLevel`: `FEW`, `STANDARD`, `MANY`
- `difficulty`: `EASY`, `MEDIUM`, `HARD`
- `questionTypes`: 数组 `[SINGLE_CHOICE, MULTIPLE_CHOICE, FILL_BLANK, SHORT_ANSWER]`
- **`quizFocus` (测验侧重方向)**:
  - `CONCEPTUAL` (概念定义 - 是什么)
  - `PRACTICAL` (场景应用/生活例子 - 怎么用)
  - `ANALYTICAL` (深度分析/逻辑推演 - 为什么)
  - `FACTUAL` (事实与细节 - 历史/数据)

#### B. 抽认卡 (Flashcard)

- `countLevel` & `difficulty` (同上)
- **`cardFocus` (提炼方向)**:
  - `AUTO` (✨ 智能适配 - 默认)
  - `KEY_TERMS` (专业术语与名词解释)
  - `CORE_ARGUMENTS` (核心观点与结论)
  - `MECHANISMS_CAUSALITY` (因果关系与运作机制)
  - `FIGURES_EVENTS` (关键人物与历史事件)
  - `FORMULAS_RULES` (公式、定律与规则)
  - `MISCONCEPTIONS_TRAPS` (易错点与认知误区)

#### C. 思维导图 (Mindmap)

- `depthLevel`: `OVERVIEW` (概览), `STANDARD` (标准), `DETAILED` (极限下钻)
- **`structureType` (结构偏好)**:
  - `AUTO` (✨ 智能适配 - 要求大模型先输出 `appliedStructure` 和 `reasoning`，再生成导图数据)
  - `CATEGORICAL` (分类罗列型 - 总分结构)
  - `LOGICAL` (逻辑推演型 - 因果链条)
  - `CHRONOLOGICAL` (时间脉络型 - 历史演进)
  - `COMPARATIVE` (对比分析型 - 阵营碰撞)
  - `PROBLEM_SOLVING` (问题解决型 - 商业分析)

#### D. 报告 (Report)

- **`format` (输出形态)**:
  - `DOCUMENT` (纯文本格式)
  - **`INTERACTIVE` (互动式)**: 后端仅生成大纲 Markdown，在段落间动态插入 `[WIDGET: QUIZ, title="第一章测试"]` 占位符。前端解析 Markdown，将占位符渲染为卡片，用户点击时再触发独立组件的生成。
- **`templateFocus` (模板偏好)**:
  - `STUDY_OVERVIEW` (学习概览)
  - `EXECUTIVE_BRIEF` (高管简报/一页纸摘要)
  - `THEMATIC_DEEP_DIVE` (主题深度解析)
  - `FAQ_STYLE` (问答解惑式)

### 5.3 UX 体验支撑 API (Prompt Suggestions)

为前端提供输入框引导，实现“不用思考即可使用”的体验：

1. **静态示例 API (`GET /api/v1/studio/prompt-examples?type=QUIZ`)**
   - 返回写死在后端的优秀 Prompt 示例 (`examples`) 和快捷按键 (`pills`)，供用户点击直接填入输入框。
2. **动态上下文推荐 API (`GET /api/v1/studio/{type}/suggestions?bookId=...`)**
   - **算力前置**：文档上传切片时，提前让大模型生成每页文档的 `summary` 和 `keywords`，存入 MySQL。
   - **极速响应**：用户打开面板时，后端极速查出关键字，交给轻量级大模型（如 300ms 延迟的小模型）生成 3 个简短的“主题标签”，前端收到后渲染为推荐 Pill 按钮。

### 5.4 泛型生成器与异步执行流程

Java

```
public interface StudioGenerator<REQ extends BaseStudioRequest, RES> {
    String getType(); // 返回鉴别器类型，如 "QUIZ"
    RES generate(REQ request);
}
```

1. **统一接收**：Controller 校验请求后，在 `studio_artifact` 插入 `PENDING` 记录，立即返回 `id`。
2. **后台生成**：虚拟线程执行 RAG 检索并要求大模型返回强类型 JSON (Structured Outputs)。
3. **全局 SSE 推送**：任务完成后，更新 payload 与状态为 `SUCCESS`，通过全局 `/api/v1/workspace/stream` 发送 `ARTIFACT_COMPLETED`，前端取消转圈动画，渲染最终卡片。

## 6. 前端交互与内联探索 (Inline Exploration)

### 6.1 前端主导的上下文桥接

实现闪卡“解释”按钮与导图节点点击直接生成对话的功能。后端**不新增**解释 API，完全由前端通过模板引擎复用主对话流接口。

- **事件拦截**：前端组件拦截节点或按钮的点击事件。
- **模板组装**：提取当前组件的上下文（如“正面题目+背面答案”或“思维导图节点层级路径”），注入预设的 Prompt 字符串模板（例如：*“请详细解释一下这张卡片提到的概念：[内容]”*）。
- **无缝衔接**：将组装好的 Prompt 发送至 `/api/v1/chat/stream`，实现静态工件与动态 AI 对话的完美闭环联动。