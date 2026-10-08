package com.yangmf.mini_nodepad.pojo.dto.studio.payload;

/**
 * 全局 SSE 进度推送的单帧负载。
 * 由后端 Ticker 在 LLM 阻塞黑盒期间轮播，前端据此渲染沉浸式分阶段 Loading。
 */
public record StudioProgressPayload(
        String stageCode,   // 阶段标识，如 "PLAN"
        String stageText,   // 前端展示文案，如 "正在规划幻灯片故事线..."
        Integer percent     // 进度条百分比，如 45
) {}