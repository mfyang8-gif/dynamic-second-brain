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
 *
 * <p>2026-10-10 调参：间隔单位由「秒」下沉到「毫秒」，节拍固定在 2~3 秒。
 * 原因：旧版 interval = max(25/size, 4) 秒，在 3~4 帧的时间线上等于 6~8 秒才跳一格，
 * 用户实测观感是「15% 卡住不动、然后直接跳到结束」。
 * 现在把 25 秒的假定耗时摊到 2~3 秒的节拍上，配合前端缓动，进度条是连续爬的。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StudioProgressTicker {

    private final WorkspaceSseService sseService;
    private final ObjectMapper objectMapper;

    /** 假定的 LLM 耗时：只用来把时间线摊平，不参与任何业务判断 */
    private static final long ASSUMED_LLM_MILLIS = 25_000L;
    /** 节拍下限 / 上限：2~3 秒。低于 2 秒会显得进度条在乱跳，高于 3 秒又回到「卡住」的观感 */
    private static final long MIN_INTERVAL_MILLIS = 2_000L;
    private static final long MAX_INTERVAL_MILLIS = 3_000L;
    /** 呼吸态每拍前进的百分点 —— 旧版 +1/拍 在 8 秒节拍下肉眼不可见 */
    private static final int BREATHING_STEP = 2;
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

        // 时间线越长，每帧分到的时间越短；夹在 2~3 秒之间保证「既连续又不喧闹」
        long intervalMillis = Math.min(MAX_INTERVAL_MILLIS,
                Math.max(MIN_INTERVAL_MILLIS, ASSUMED_LLM_MILLIS / timeline.size()));

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
                    cp = Math.min(MAX_BREATHING_PERCENT, currentPercent.addAndGet(BREATHING_STEP));
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

        }, 600, intervalMillis, TimeUnit.MILLISECONDS);

        return () -> future.cancel(false);
    }
}