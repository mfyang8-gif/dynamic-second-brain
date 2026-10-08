# 前端开发规范：互动式报告 (Interactive Report) 渲染与交互机制

**背景与架构思路：**

互动式报告采用“内容与状态解耦”的架构。后端传递的数据包含两部分：不可变的纯净 `markdown` 文本（内含组件占位符）以及动态的 `widgetStates` 状态字典。前端的核心职责是拦截 Markdown 中的占位符，并结合状态字典，渲染出对应的动态交互组件，实现类似 Google NotebookLM 的沉浸式阅读体验。

## 1. 核心数据源 (Payload 结构)

前端在获取报告详情时，会拿到如下核心数据结构：

TypeScript

```
interface ReportPayload {
  markdown: string; // 纯净的报告正文，包含 [WIDGET:...] 占位符
  widgetStates: Record<string, WidgetState>; // 状态字典，Key 为 widgetId
}

interface WidgetState {
  status: 'RECOMMENDED' | 'DISMISSED' | 'GENERATING' | 'GENERATED_SHOW' | 'GENERATED_HIDE';
  targetArtifactId?: string | null; // 已生成的真实工件ID（生成后才会存在）
}
```

**⚠️ 核心铁律：** 前端在任何交互下，都**绝对不允许**去修改、替换 `markdown` 字符串本身。所有的 UI 变化必须全部通过修改或更新 `widgetStates` 字典来驱动视图重绘。

## 2. Markdown 解析与拦截逻辑

前端需要自定义 Markdown 渲染器（如编写 `markdown-it` 插件，或在 Vue/React 渲染完 AST 后进行 DOM 替换），专门拦截特定格式的占位符：

**占位符标准格式：**

```
[WIDGET:组件类型, id="唯一标识", title="卡片标题", prompt="生成指令"]
```

**解析参考正则：**

JavaScript

```
const widgetRegex = /\[WIDGET:(\w+),\s*id="([^"]+)",\s*title="([^"]+)",\s*prompt="([^"]+)"\]/g;
```

**渲染替换流程：**

当解析器匹配到该正则时，不将其渲染为普通文本，而是替换为前端的自定义外壳组件（例如 `<InteractiveWidget/>`），并将正则提取出的 `type`, `id`, `title`, `prompt` 作为 Props 传递给该组件。

## 3. 状态机与 UI 视图映射规则

在 `<InteractiveWidget/>` 组件内部，必须实时监听 `widgetStates[id].status`，并根据状态渲染不同的 UI：

| **当前状态 (Status)** | **前端 UI 渲染表现**                                         |
| --------------------- | ------------------------------------------------------------ |
| `DISMISSED`           | **返回 `null`（彻底隐藏）**。占位符所在位置不留任何痕迹。    |
| `RECOMMENDED`         | **渲染“推荐卡片”**。带有紫色主题色、图标、标题、以及右侧的“添加”按钮。右上角带有关闭“X”图标。 |
| `GENERATING`          | **渲染“加载动画”**。骨架屏或转圈动画，提示“正在生成 [类型]...”。 |
| `GENERATED_SHOW`      | **渲染“真实交互组件”**。直接嵌入对应的 `<QuizViewer>`, `<MindmapViewer>` 等独立组件。**顶部必须附带一个“显示/隐藏”的圆角矩形切换开关**（当前高亮“显示”）。 |
| `GENERATED_HIDE`      | **渲染“折叠条”**。仅显示一个细条状的卡片（提示“已生成 [标题] 组件”）。顶部的“显示/隐藏”开关高亮“隐藏”。 |

## 4. 交互事件流转 (Action Flows)

所有用户在组件上的点击操作，都必须同步调用后端接口，并更新本地字典，以保证用户刷新页面后状态依然持久化。

### 场景 A：用户点击“X”关闭推荐

1. 触发点击事件。
2. 调用后端 API：`PUT /api/v1/studio/report/{reportId}/widget/{widgetId}/state`，传入状态 `DISMISSED`。
3. 接口成功后，将本地字典 `widgetStates[id].status` 设为 `DISMISSED`。
4. 视图自动重绘，卡片消失。

### 场景 B：用户点击“添加”并“立即生成”

1. 点击“添加”，前端弹出一个二次确认弹窗（预填充提取到的 `prompt` 属性内容）。
2. 用户点击“立即生成”，将本地字典 `widgetStates[id].status` 设为 `GENERATING`（此时 UI 变为转圈动画）。
3. 前端向后端的**独立组件生成接口**（如 `/api/v1/studio/generate`）发起请求，传入 `bookId` 和 `prompt`。
4. 生成成功，拿到返回的真实 `artifactId`（如 `10086`）。
5. 再次调用状态更新 API，传入状态 `GENERATED_SHOW` 和 `targetArtifactId=10086`。
6. 更新本地字典，视图重绘，展示完整的思维导图或测验。

### 场景 C：用户点击“显示/隐藏”圆角矩形开关

1. 当状态为 `GENERATED_SHOW`，用户点击“隐藏”：
   - 调接口保存状态 `GENERATED_HIDE`。
   - 更新本地字典，大组件收起变为折叠条。
2. 当状态为 `GENERATED_HIDE`，用户点击“显示”：
   - 调接口保存状态 `GENERATED_SHOW`。
   - 更新本地字典，大组件重新展开。

## 5. 前端组件骨架示例 (Vue 3 组合式 API 伪代码)

代码段

```
<template>
  <div class="interactive-widget-container">
    <!-- 1. 彻底隐藏 -->
    <template v-if="currentState === 'DISMISSED'"></template>

    <!-- 2. 推荐态 -->
    <div v-else-if="currentState === 'RECOMMENDED'" class="recommend-card">
      <div class="card-header">
        <span>推荐 · {{ widgetType }}</span>
        <button @click="handleDismiss">X</button>
      </div>
      <div class="card-body">
        <h4>{{ title }}</h4>
        <button @click="openGenerateModal">添加</button>
      </div>
    </div>

    <!-- 3. 生成中 -->
    <div v-else-if="currentState === 'GENERATING'" class="loading-state">
      <Spinner /> 正在生成{{ title }}...
    </div>

    <!-- 4 & 5. 已生成态 (展开/收起) -->
    <div v-else class="generated-container">
      <!-- 圆角矩形开关 -->
      <div class="toggle-switch">
        <div :class="{ active: currentState === 'GENERATED_SHOW' }" @click="toggleShow(true)">显示</div>
        <div :class="{ active: currentState === 'GENERATED_HIDE' }" @click="toggleShow(false)">隐藏</div>
      </div>
      
      <!-- 真实组件挂载点 (仅在 SHOW 时渲染内容) -->
      <component 
        v-show="currentState === 'GENERATED_SHOW'"
        :is="getComponentByType(widgetType)" 
        :artifact-id="targetArtifactId" 
      />
    </div>
  </div>
</template>
```