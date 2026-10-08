package com.yangmf.mini_nodepad.service.studio;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yangmf.mini_nodepad.pojo.dto.WorkspaceEvent;
import com.yangmf.mini_nodepad.pojo.dto.studio.payload.StudioProgressPayload;
import com.yangmf.mini_nodepad.service.WorkspaceSseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 全局沉浸式进度轮播引擎（双态版）。
 * 状态1【轮播态】：按动态计算的间隔，推送 Generator 定义的真实进度帧。
 * 状态2【呼吸态】：若真实帧推完后大模型仍未返回（如触发了 @Retryable），进入兜底缓慢递增模式，封顶 98%，避免假死。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StudioProgressTicker {

    private final WorkspaceSseService sseService;
    private final ObjectMapper objectMapper;

    private static final long ASSUMED_LLM_SECONDS = 25;
    private static final long MIN_INTERVAL_SECONDS = 4;
    private static final int MAX_BREATHING_PERCENT = 98;

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4, r -> {
        Thread t = new Thread(r, "studio-progress-ticker");
        t.setDaemon(true);
        return t;
    });

    public AutoCloseable start(Long userId, String artifactId, List<StudioProgressPayload> timeline) {
        if (timeline == null || timeline.isEmpty()) {
            return () -> { };
        }

        long intervalSeconds = Math.max(ASSUMED_LLM_SECONDS / timeline.size(), MIN_INTERVAL_SECONDS);

        AtomicInteger index = new AtomicInteger(0);
        AtomicInteger currentPercent = new AtomicInteger(0);
        AtomicReference<String> currentStageCode = new AtomicReference<>("");
        AtomicReference<String> currentStageText = new AtomicReference<>("");

        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(() -> {
            int i = index.getAndIncrement();
            StudioProgressPayload payloadToPush;

            if (i < timeline.size()) {
                // ============== 阶段 1：真实轮播态 ==============
                StudioProgressPayload frame = timeline.get(i);
                currentPercent.set(frame.percent());
                currentStageCode.set(frame.stageCode());
                currentStageText.set(frame.stageText());
                payloadToPush = frame;
            } else {
                // ============== 阶段 2：呼吸兜底态 ==============
                int cp = currentPercent.get();
                if (cp < MAX_BREATHING_PERCENT) {
                    cp = currentPercent.incrementAndGet();
                    payloadToPush = new StudioProgressPayload(
                            currentStageCode.get() + "_BREATHING",
                            currentStageText.get(),
                            cp
                    );
                } else {
                    // ============== 阶段 3：封顶休眠 ==============
                    return;
                }
            }

            try {
                String json = objectMapper.writeValueAsString(payloadToPush);
                sseService.pushToUser(userId, WorkspaceEvent.artifactProgress(artifactId, json));
            } catch (Exception e) {
                log.warn("进度帧推送失败 | artifactId={}, stage={}", artifactId, payloadToPush.stageCode(), e);
            }

        }, 1, intervalSeconds, TimeUnit.SECONDS);

        return () -> future.cancel(false);
    }
}