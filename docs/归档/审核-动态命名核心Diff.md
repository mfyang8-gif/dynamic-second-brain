# 工件动态命名 dynamic_title —— 核心 Diff（后端，第二轮·含并发修复）

> 状态：**仅审核，未写入任何代码文件**。确认后再落盘 + `mvn -o -DskipTests compile`。
>
> 本轮相对第一版方案的两处硬修复：
> 1. **ThreadLocal 上下文**：`bookName` 在 HTTP 线程（`verifyOwnership` 之后）预取，以 `String` 闭包下传，异步线程内**零次**调用 `bookService`。
> 2. **多线程脏写**：暂定名只做 `LambdaUpdateWrapper` 单列更新（且带状态闸门）；精修名收敛进主线程，`artifact.setDynamicTitle()` 后与主 `SUCCESS` 共用**唯一一次** `updateById`。

---

## 0. DDL（唯一需要人工执行的 SQL）

```sql
ALTER TABLE studio_artifact
    ADD COLUMN dynamic_title VARCHAR(128) NULL COMMENT 'AI 动态生成的场景化标题' AFTER title;
```

> `title` 保留并继续存 `topic`，作为前端 `dynamicTitle ?? title` 的兜底，**不删不改**。

---

## 1. `pojo/entity/StudioArtifact.java`（+4 行）

```diff
     @Schema(description = "工件标题")
     private String title;
 
+    @Schema(description = "AI 动态生成的场景化标题（两阶段命名终值，可为空）")
+    private String dynamicTitle;
+
     @TableField(typeHandler = JacksonTypeHandler.class)
     @Schema(description = "结构化JSON数据（多态）")
     private ArtifactPayload payload;
```

---

## 2. `pojo/dto/WorkspaceEvent.java`（+4 行）

```diff
     public static WorkspaceEvent artifactProgress(String artifactId, String progressJson) {
         return new WorkspaceEvent("ARTIFACT_PROGRESS", artifactId, progressJson);
     }
+
+    /** 命名事件：data 直接就是标题字符串，前端拿去原位替换加载态文案 */
+    public static WorkspaceEvent artifactTitled(String artifactId, String dynamicTitle) {
+        return new WorkspaceEvent("ARTIFACT_TITLED", artifactId, dynamicTitle);
+    }
 }
```

---

## 3. `ai/aiservice/ArtifactTitleAssistant.java`（新文件）

沿用项目现有 LangChain4j 接口式声明（与 `QueryRewriteAssistant` 同款）。

```java
package com.yangmf.mini_nodepad.ai.aiservice;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

public interface ArtifactTitleAssistant {

    @SystemMessage("""
    你是资深内容编辑，为知识库衍生工件命名。

    【DRAFT 阶段（仅凭用户诉求）】
    输出一个"场景化暂定名"，形如「关于群体心理学的思维导图」，平实、具体、可辨认即可。

    【FINAL 阶段（已拿到成品内容）】
    输出一个具象、有辨识度的标题，形如《狂热与博弈：群体心理的思维脉络》。
    允许使用主副标题（用中文冒号分隔），但必须有信息量，不得是"XX报告""XX测验"这类空壳。

    【硬性规则】
    1. 只输出标题本身，禁止任何解释、序号、引号、书名号、前后缀
    2. 长度 6~24 个汉字
    3. 语言与用户诉求保持一致
    4. 禁止臆造内容摘要里不存在的结论
    """)
    @UserMessage("""
    【阶段】{{stage}}
    【工件类型】{{typeName}}
    【知识库名称】{{bookName}}
    【用户诉求】{{topic}}
    【内容节选】{{digest}}
    """)
    String nameIt(@V("stage") String stage,
                  @V("typeName") String typeName,
                  @V("bookName") String bookName,
                  @V("topic") String topic,
                  @V("digest") String digest);
}
```

---

## 4. `ai/config/AssistantConfig.java`（+6 行）

```diff
     @Bean
     public QueryRewriteAssistant queryAssistant() {
         return AiServices.builder(QueryRewriteAssistant.class)
                 .chatModel(qwenChatModel)
                 .build();
     }
 
+    /** 工件两阶段命名助手（DRAFT 暂定名 / FINAL 精修名） */
+    @Bean
+    public ArtifactTitleAssistant artifactTitleAssistant() {
+        return AiServices.builder(ArtifactTitleAssistant.class)
+                .chatModel(qwenChatModel)
+                .build();
+    }
+
```

> `AssistantConfig` 已 `import com.yangmf.mini_nodepad.ai.aiservice.*;`，无需新增 import。

---

## 5. `service/impl/StudioServiceImpl.java`（核心）

### 5.1 构造注入（+2 参）

```diff
     private final BookService bookService;
+    private final ArtifactTitleAssistant titleAssistant;
+    private final Executor aiTaskExecutor;
     private final AliOssUtil aliOssUtil;
@@
                              @Qualifier(ThreadPoolConfig.STUDIO_TASK_EXECUTOR) Executor studioTaskExecutor,
                              BookService bookService,
+                             ArtifactTitleAssistant titleAssistant,
+                             @Qualifier(ThreadPoolConfig.AI_TASK_EXECUTOR) Executor aiTaskExecutor,
                              AliOssUtil aliOssUtil,
@@
         this.bookService = bookService;
+        this.titleAssistant = titleAssistant;
+        this.aiTaskExecutor = aiTaskExecutor;
         this.aliOssUtil = aliOssUtil;
```

> 暂定名走 `aiTaskExecutor`（core 5 / queue 100），**不占** `studioTaskExecutor`（core 3）的生成名额。

### 5.2 `submit()` —— HTTP 线程预取书名 + 派发两个任务

```diff
         bookService.verifyOwnership(request.getBookId(), userId);
+
+        // 预取知识库名称：异步线程里没有 BaseContext，再调 bookService 必抛 401。
+        // 因此必须在 HTTP 请求线程（verifyOwnership 之后）取好，
+        // 以纯 String 经闭包下传给 executeGeneration / applyDraftTitle。
+        String bookName = resolveBookName(request.getBookId());
+
         StudioArtifact artifact = StudioArtifact.builder()
                 .userId(String.valueOf(userId))
                 .bookId(request.getBookId())
                 .type(type)
                 .status(ArtifactStatus.PENDING)
                 .title(request.getTopic())
                 .build();
         artifactMapper.insert(artifact);
         log.info("Studio 工件已创建 | id={}, type={}, userId={}", artifact.getId(), type, userId);
 
+        // 阶段一：场景化暂定名（Best-Effort，与主生成并行）
+        submitDraftTitle(artifact.getId(), userId, type, request.getTopic(), bookName);
+
-        studioTaskExecutor.execute(() -> executeGeneration(artifact.getId(), userId, type, request));
+        studioTaskExecutor.execute(() -> executeGeneration(artifact.getId(), userId, type, request, bookName));
 
         return artifact.getId();
```

### 5.3 `executeGeneration()` —— 精修名并入主线程，唯一一次 updateById

```diff
     private void executeGeneration(String artifactId, Long userId,
-                                   ArtifactType type, BaseStudioRequest request) {
+                                   ArtifactType type, BaseStudioRequest request,
+                                   String bookName) {
@@
             ArtifactPayload payload;
             try (AutoCloseable ticker = progressTicker.start(userId, artifactId, timeline)) {
                 String context = contextBuilder.buildContext(request);
                 payload = generator.generate(request, context);
 
+                // 阶段二：精修命名。
+                // 收敛在主线程单线程路径内：只写内存对象，不做任何独立落库。
+                // 下面那次 updateById 是本次生成的唯一写库点，
+                // 避免与暂定名线程互相覆盖 status / payload。
+                String refined = refineTitle(type, request.getTopic(), bookName, payload);
+                if (refined != null && !refined.isEmpty()) {
+                    artifact.setDynamicTitle(refined);
+                }
+
                 artifact.setStatus(ArtifactStatus.SUCCESS);
                 artifact.setPayload(payload);
                 artifactMapper.updateById(artifact);
             }
 
+            // 先推终值标题，再推完成事件：前端不会看到"先完成、后改名"的闪烁
+            if (artifact.getDynamicTitle() != null) {
+                workspaceSseService.pushToUser(userId,
+                        WorkspaceEvent.artifactTitled(artifactId, artifact.getDynamicTitle()));
+            }
+
             String payloadJson = objectMapper.writeValueAsString(payload);
             workspaceSseService.pushToUser(userId,
                     WorkspaceEvent.artifactCompleted(artifactId, payloadJson));
```

### 5.4 新增四个私有方法（全部 Best-Effort，异常不外抛）

```java
    /** HTTP 线程内预取知识库名称；失败退化为空串，不阻断提交 */
    private String resolveBookName(String bookId) {
        try {
            BookVO book = bookService.getBookById(bookId);
            return (book != null && book.getName() != null) ? book.getName() : "";
        } catch (Exception e) {
            log.warn("预取知识库名称失败，命名将不带库名 | bookId={}", bookId, e);
            return "";
        }
    }

    /**
     * 阶段一派发：严格 Best-Effort。
     * 捕获一切异常（含线程池拒绝执行的 RejectedExecutionException），
     * 失败静默 —— 前端会自动回退到 topic 展示。
     */
    private void submitDraftTitle(String artifactId, Long userId, ArtifactType type,
                                  String topic, String bookName) {
        try {
            aiTaskExecutor.execute(() -> applyDraftTitle(artifactId, userId, type, topic, bookName));
        } catch (Exception e) {
            log.warn("暂定名任务提交失败（已忽略）| id={}", artifactId, e);
        }
    }

    /** 阶段一落库：只更新 dynamic_title 单列，带"生成中"状态闸门防覆盖精修名 */
    private void applyDraftTitle(String artifactId, Long userId, ArtifactType type,
                                 String topic, String bookName) {
        try {
            String draft = titleAssistant.nameIt(
                    "DRAFT", type.getDescription(), bookName, safeTopic(topic), "");
            String cleaned = cleanTitle(draft);
            if (cleaned.isEmpty()) {
                return;
            }
            // ① 用 update(null, wrapper) 单列更新，绝不用 updateById 全行写：
            //    全行写会把主线程的 status / payload 覆盖掉（脏写、工件假死）。
            // ② 状态闸门：仅当工件仍在 PENDING / PROCESSING 时才写暂定名。
            //    若主生成已 SUCCESS（精修名已落库）或已 FAILED，rows=0，直接放弃，
            //    杜绝"暂定名迟到反杀精修名"。
            int rows = artifactMapper.update(null, new LambdaUpdateWrapper<StudioArtifact>()
                    .eq(StudioArtifact::getId, artifactId)
                    .in(StudioArtifact::getStatus, ArtifactStatus.PENDING, ArtifactStatus.PROCESSING)
                    .set(StudioArtifact::getDynamicTitle, cleaned));
            if (rows == 0) {
                log.debug("工件已不在生成中，跳过暂定名写入 | id={}", artifactId);
                return;
            }
            workspaceSseService.pushToUser(userId, WorkspaceEvent.artifactTitled(artifactId, cleaned));
            log.info("暂定名已应用 | id={}, title={}", artifactId, cleaned);
        } catch (Exception e) {
            // 异步线程内任何异常都不允许冒泡（会打进线程池未捕获处理器）
            log.warn("暂定名生成失败（已忽略）| id={}", artifactId, e);
        }
    }

    /** 阶段二：主线程内精修。失败返回 null，保留暂定名 */
    private String refineTitle(ArtifactType type, String topic, String bookName, ArtifactPayload payload) {
        try {
            String refined = titleAssistant.nameIt(
                    "FINAL", type.getDescription(), bookName, safeTopic(topic), digestOf(payload));
            return cleanTitle(refined);
        } catch (Exception e) {
            log.warn("精修命名失败，保留暂定名 | type={}", type, e);
            return null;
        }
    }

    /** 成品 JSON 节选，作为精修命名的内容依据 */
    private String digestOf(ArtifactPayload payload) {
        if (payload == null) {
            return "";
        }
        try {
            String json = objectMapper.writeValueAsString(payload).replaceAll("\\s+", " ").trim();
            return json.length() > 1500 ? json.substring(0, 1500) : json;
        } catch (Exception e) {
            log.warn("工件内容摘要序列化失败", e);
            return "";
        }
    }

    /** topic 判空兜底，防止 null 污染 Prompt */
    private String safeTopic(String topic) {
        return (topic == null || topic.isBlank()) ? "（用户未指定主题，请依据内容自拟）" : topic.trim();
    }

    /** 标题清洗：去引号/书名号/换行/模型常用前缀，截断到 128 字以内（对齐列宽） */
    private String cleanTitle(String raw) {
        if (raw == null) {
            return "";
        }
        String t = raw.replaceAll("[《》\"'`\\r\\n]", "")
                .replaceAll("\\s+", " ")
                .replaceFirst("^(标题|名称)\\s*[:：]\\s*", "")
                .trim();
        return t.length() > 120 ? t.substring(0, 120) : t;
    }
```

### 5.5 import 增量

```diff
 import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
+import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
 import com.fasterxml.jackson.databind.ObjectMapper;
 
+import com.yangmf.mini_nodepad.ai.aiservice.ArtifactTitleAssistant;
 import com.yangmf.mini_nodepad.ai.component.StudioGenerator;
@@
 import com.yangmf.mini_nodepad.pojo.entity.StudioArtifact;
+import com.yangmf.mini_nodepad.pojo.vo.BookVO;
 import com.yangmf.mini_nodepad.properties.PandocProperties;
```

---

## 6. 不动的地方（确认无误）

| 位置 | 结论 |
|---|---|
| `StudioArtifactMapper` | 该表无 XML，MyBatis-Plus 驼峰自动映射 `dynamicTitle ↔ dynamic_title`，无需改动 |
| Controller / `StudioService` 接口 | 方法签名未变 |
| 各 `StudioGenerator` | 未触及 |
| `markFailed` | 保持 `updateById` 全行写（失败态独占路径，无并发竞争） |
| `ChatStreamService`（思考态） | 已在推 `status(phase, message)`，后端零改动 |

---

## 7. 写库时序（防脏写证明）

```
HTTP 线程   submit()
              ├─ resolveBookName()            ← 唯一一次 bookService 调用
              ├─ insert(artifact)             ← status=PENDING, title=topic
              ├─ aiTaskExecutor  → applyDraftTitle()   ── 单列 UPDATE dynamic_title (闸门: PENDING|PROCESSING)
              └─ studioTaskExecutor → executeGeneration()
                       ├─ UPDATE status=PROCESSING      （updateById，全行）
                       ├─ …LLM 生成…
                       ├─ setDynamicTitle(refined)      （仅内存）
                       ├─ UPDATE SUCCESS + payload + dynamic_title  ←【唯一一次】
                       └─ SSE: ARTIFACT_TITLED → ARTIFACT_COMPLETED
```

两条写路径只在一处相交（`dynamic_title` 列），且被状态闸门隔离：
暂定名只能在 `PENDING|PROCESSING` 期间写；精修名与 `SUCCESS` 同一次全行写。
任何顺序下都不会出现「status 被覆盖回 PROCESSING」「payload 被抹掉」「FAILED 被冲掉」「暂定名反杀精修名」。
