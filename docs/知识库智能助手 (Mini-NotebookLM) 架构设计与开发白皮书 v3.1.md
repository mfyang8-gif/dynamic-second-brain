# 知识库智能助手 (Mini-NotebookLM) 架构设计与开发白皮书 v3.1

**核心聚焦：Studio 衍生工具箱模块详细设计**

## 1. 核心架构原则 (Core Design Principles)

- **数据与表现解耦 (Data & Presentation Separation)**：针对重度排版的工件（如 PPT、报告），大模型仅负责“大脑”工作，生成高度结构化的 JSON 或 Markdown 大纲。繁重的“双手”排版工作交由外部成熟的工具链（如 MCP Server、Pandoc）完成。
- **确定性编排 (Deterministic Pipeline)**：坚决不在大模型侧使用易超时的 Function Calling 来调用外部文件生成工具。采用后端 Java 虚拟线程作为“中枢调度员”，直调外部 API，保障极高的容错率与执行效率。
- **结构化输出优先 (Structured Output First)**：废除手动正则解析 JSON 的方式。全面拥抱 LangChain4j 的 `@AiService`，通过定义强类型的 POJO Payload，让框架自动处理底层 JSON Schema 映射与反序列化。
- **极速 UX 与异步兜底**：前端收到生成成功事件后，优先利用本地大纲数据（JSON/Markdown）进行秒级组件渲染；物理文件的下载链接作为附加资产提供。

## 2. Studio 核心组件 Schema 设计与落地方案

BaseRequest 共享参数：`bookId` (知识库 ID)、`sourcePageIds` (指定页面)、`topic` (主题)、`language` (语言)。

### A. 测验 (Quiz)

- **参数 Schema**：
  - `countLevel`: FEW (5题), STANDARD (10题), MANY (15题)
  - `difficulty`: EASY, MEDIUM, HARD
  - `quizFocus`: CONCEPTUAL (概念定义), PRACTICAL (场景应用), ANALYTICAL (深度分析), FACTUAL (事实细节)
- **落地方案**：
  - **后端**：调用 `@AiService` 生成强类型 JSON，校验选项完整性。
  - **前端**：原生渲染互动答题卡片，交卷后展示得分雷达与错题解析，支持一键追问。

### B. 抽认卡 (Flashcard)

- **参数 Schema**：
  - `countLevel` & `difficulty` (同上)
  - `cardFocus`: AUTO, KEY_TERMS (术语), CORE_ARGUMENTS (核心观点), MECHANISMS_CAUSALITY (因果机制), MISCONCEPTIONS_TRAPS (易错点)
- **落地方案**：
  - **后端**：利用 AI 进行知识原子化切割，生成包含 `front`, `back`, `category` 的强类型 JSON。
  - **前端**：渲染为 3D 翻转卡组（Flip Card），背面集成“深入解释”按键，无缝衔接到主对话流。

### C. 思维导图 (Mindmap)

- **参数 Schema**：
  - `depthLevel`: OVERVIEW (概览), STANDARD (标准), DETAILED (极限下钻)
  - `structureType`: AUTO, CATEGORICAL (分类罗列), LOGICAL (逻辑推演), CHRONOLOGICAL (时间脉络), PROBLEM_SOLVING (问题解决)
- **落地方案**：
  - **后端**：约束大模型生成每层不超过 5-6 个分支的嵌套递归 JSON 树，并输出 `reasoning`。
  - **前端**：使用 `Markmap` 或 `React Flow` 将 JSON 渲染为可缩放、折叠的交互式树状图。

### D. 报告 (Report)

- **参数 Schema**：
  - `format`: DOCUMENT (纯文本格式), INTERACTIVE (互动式，包含 `[WIDGET:QUIZ]` 等交互占位符)
  - `templateFocus`: STUDY_OVERVIEW (学习概览), EXECUTIVE_BRIEF (高管简报), THEMATIC_DEEP_DIVE (主题深挖)
- **落地方案 (两阶段架构)**：
  - **阶段一 (AI)**：生成排版规范的标准 Markdown 文本。
  - **阶段二 (外部渲染)**：后端将占位符静态化降级后，**调用 Pandoc 或 Gotenberg 微服务**，将 Markdown 完美渲染为企业级排版的 `.docx` 或 `.pdf`，上传 OSS 并获取 `fileUrl`。
  - **前端**：使用 Markdown 渲染器就地展示内容，对 `[WIDGET:...]` 语法进行拦截并渲染为互动挂载点。

### E. 演示文稿 (Presentation/PPT)

- **参数 Schema**：
  - `lengthLevel`: BRIEF (约5-7页), STANDARD (约10-15页), COMPREHENSIVE (20页以上)
  - `targetAudience`: GENERAL (普通大众), TECHNICAL (专业技术人员), EXECUTIVE (高管/投资人)
  - `visualStyle`: BUSINESS (商务严谨), ACADEMIC (学术研究), MINIMALIST (极简大字报)
- **落地方案 (外部 BaaS 深度整合)**：
  - **阶段一 (AI)**：后端调用自有 Qwen 模型生成包含总标题、各页 `bullets` 和 `speakerNotes` 的结构化 Markdown 大纲。
  - **阶段二 (ChatPPT MCP Server)**：
    1. 后端作为 MCP Client 调用 `build_ppt` 工具，传入带指令的 Markdown。   
    2. 若需强行控制模板，调用 `replace_template_ppt`。   
    3. 轮询 `query_ppt` 获取成功状态，通过 `download_ppt` 和 `editor_ppt` 获取下载链接与 Web 在线编辑链接。   
  - **前端**：使用生成的本地 JSON 数据秒出大纲列表预览卡片，卡片底部提供 **“📥 下载 PPTX”** 与 **“✏️ 在线排版美化”** 入口。

## 3. 数据库与 Payload 设计规范

采用单表多态存储，`studio_artifact` 表统一记录，差异化数据存入 `payload` JSON 列。

### 多态 Payload 基类

Java

```
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "artifactType")
@JsonSubTypes({
    @JsonSubTypes.Type(value = QuizPayload.class, name = "QUIZ"),
    @JsonSubTypes.Type(value = ReportPayload.class, name = "REPORT"),
    @JsonSubTypes.Type(value = PresentationPayload.class, name = "PRESENTATION") // ...其他类型
})
public abstract class ArtifactPayload {
    private String artifactType;
}
```

### 综合 Payload 数据结构示例

JSON

```
// PresentationPayload 示例
{
  "artifactType": "PRESENTATION",
  "presentationTitle": "商业模式深度复盘与规划",
  "presentationSubtitle": "基于2026年核心战略知识库",
  "slides": [
    {
      "slideNumber": 1,
      "title": "市场现状与竞争格局",
      "bullets": [
        "行业渗透率已突破瓶颈期",
        "复购率成为第一核心指标"
      ],
      "speakerNotes": "各位领导好，首先来看第一部分..."
    }
  ],
  "pptId": "chatppt-task-12345",
  "fileUrl": "https://download.chatppt.com/files/abc.pptx",
  "editorUrl": "https://editor.chatppt.com/view?token=xyz"
}
```

## 4. UX 体验支撑 API (Prompt Suggestions)

引导用户实现“不用思考即可使用”的智能输入框体验。

### 4.1 静态示例 API (`GET /api/v1/studio/prompt-examples?type={type}`)

- **机制**：内存直返，无需大模型算力。
- **功能**：下发后端预设的精选 Prompt 模板和 Pills 按键，点击直接填充至输入框。

### 4.2 动态上下文推荐 API (`GET /api/v1/studio/{type}/suggestions?bookId={id}`)

- **机制**：**算力前置 + 小模型秒级生成**。
- **流程**：
  1. 在知识库文档解析（Ingest）阶段，提前抽取高频 `keywords` 存入 MySQL。
  2. 用户打开 Studio 面板时，后端极速查出该知识库关键词。
  3. 组装极简指令交由轻量级大模型（如 Qwen-Turbo），实时生成 3 个 10 字以内的高相关性“主题标签”。
  4. 前端渲染为动态推荐 Pill 按钮，实现毫秒级“懂我所想”的交互体验。