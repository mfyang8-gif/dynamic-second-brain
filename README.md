#  动态第二大脑 (Dynamic Second Brain)

## 一、 项目愿景

本项目旨在从零打造一个支持无脑碎片输入、自动清洗排版、智能去重合并，并支持多模态 RAG 检索的个人知识库系统。告别静态笔记，构建一个能够自我进化的“动态”大脑。

**核心技术底座：**

* **后端框架：** Spring Boot 3.x
* **大模型编排：** LangChain4j
* **向量数据库：** Qdrant (高维向量存储与相似度计算)
* **关系型数据库：** MySQL 8.0 (持久化业务元数据)
* **缓存与中间件：** Redis

---

## 二、 核心数据模型 (ER 骨架)

### 1. `book` (知识库/笔记本表)

用于管理知识的大类（如：心理学、计算机网络）。

| 字段名        | 类型         | 说明            |
| :------------ | :----------- | :-------------- |
| `id`          | VARCHAR(32)  | 唯一主键        |
| `name`        | VARCHAR(64)  | 书名/知识库名称 |
| `description` | VARCHAR(255) | 总体描述        |
| `created_at`  | DATETIME     | 创建时间        |

### 2. `page` (知识点/书页表)

用于存储具体的知识碎片、笔记或清洗后的长文。

| 字段名            | 类型         | 说明                                              |
| :---------------- | :----------- | :------------------------------------------------ |
| `id`              | VARCHAR(32)  | 唯一主键                                          |
| `book_id`         | VARCHAR(32)  | 外键，关联 `book` 表                              |
| `title`           | VARCHAR(128) | AI 自动生成的标题                                 |
| `summary`         | VARCHAR(512) | AI 自动生成的摘要                                 |
| `content`         | TEXT         | 最终落库的文本内容 (Markdown 格式)                |
| `source_type`     | VARCHAR(32)  | 来源：`MANUAL`(手动), `WEB`(网络), `HYBRID`(混合) |
| `qdrant_point_id` | VARCHAR(64)  | 关联至 Qdrant 的向量 ID (核心关联字段)            |
| `auto_optimize`   | TINYINT(1)   | 是否允许 AI 自动清洗排版 (0=否, 1=是)             |
| `allow_ai_modify` | TINYINT(1)   | 是否允许后续被相似知识合并 (0=否, 1=是)           |

---

## 三、 迭代里程碑 (Milestones)

### 🚩 Milestone 1: 基建与骨架 (MVP v0.5)

- [ ] 搭建 Spring Boot 工程，集成全局统一返回对象 `Result<T>`。
- [ ] 配置全局异常处理器 `@RestControllerAdvice`。
- [ ] 严格遵守 Alibaba Java 编码规约。
- [ ] 完成 `Book` 和 `Page` 的基础 CRUD 接口与数据库联调。

### 🚩 Milestone 2: 核心 AI 流水线 (MVP v1.0)

- [ ] 集成 LangChain4j，开发大模型“文本清洗拦截器” (Structured Output)。
- [ ] 打通 Qdrant，实现文本自动分块 (Chunking) 与向量化 (Embedding)。
- [ ] **实现智能合并引擎：** 知识入库前查重，若相似度达标且 `allow_ai_modify=true`，触发大模型融合更新。

### 🚩 Milestone 3: 场景应用与拓展 (v2.0 规划)

- [ ] 开发基于指定 `Book` 的 RAG 智能对话接口。
- [ ] 接入网络搜索工具，补充 `WEB` 来源知识。
- [ ] 整合定时任务，开发基于艾宾浩斯记忆曲线的复习推送模块。

---

## 四、 研发纪律规范

1. **API-First：** 任何新功能开发前，优先定义并固化 RESTful 接口契约。
2. **职责分离：** Controller 层绝对不写业务逻辑，LangChain4j/Qdrant 调用必须下沉至 Service 层。
3. **日志规范：** 禁用 `System.out` 和无意义的 Emoji，强制使用 SLF4J 记录关键业务流转与异常堆栈。
4. **测试兜底：** 核心智能合并逻辑必须编写 JUnit 单元测试，确保逻辑闭环。