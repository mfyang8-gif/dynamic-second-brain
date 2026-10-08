package com.yangmf.mini_nodepad.service;// service/impl/WorkspaceSseService.java


import com.fasterxml.jackson.databind.ObjectMapper;
import com.yangmf.mini_nodepad.pojo.dto.WorkspaceEvent;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class WorkspaceSseService {

    private final ObjectMapper objectMapper;

    private final ConcurrentHashMap<Long, CopyOnWriteArraySet<SseEmitter>> userConnections = new ConcurrentHashMap<>();

    private ScheduledExecutorService heartbeatScheduler;

    @Value("${workspace.sse.heartbeat-interval-seconds:15}")
    private int heartbeatIntervalSeconds;

    @Value("${workspace.sse.timeout:3600000}")
    private long sseTimeout;

    @PostConstruct
    public void init() {
        heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ws-heartbeat");
            t.setDaemon(true);
            return t;
        });
        heartbeatScheduler.scheduleAtFixedRate(
                this::sendHeartbeat,
                heartbeatIntervalSeconds,
                heartbeatIntervalSeconds,
                TimeUnit.SECONDS
        );
        log.info("Workspace SSE 心跳调度器已启动 | interval={}s", heartbeatIntervalSeconds);
    }

    @PreDestroy
    public void destroy() {
        if (heartbeatScheduler != null) {
            heartbeatScheduler.shutdownNow();
        }
        userConnections.values().forEach(emitters ->
                emitters.forEach(emitter -> {
                    try { emitter.complete(); } catch (Exception ignored) {}
                })
        );
        userConnections.clear();
        log.info("Workspace SSE 所有连接已清理");
    }

    public SseEmitter connect(Long userId) {
        SseEmitter emitter = new SseEmitter(sseTimeout);

        CopyOnWriteArraySet<SseEmitter> userEmitters =
                userConnections.computeIfAbsent(userId, k -> new CopyOnWriteArraySet<>());
        userEmitters.add(emitter);

        Runnable onDisconnect = () -> {
            userEmitters.remove(emitter);
            if (userEmitters.isEmpty()) {
                userConnections.remove(userId);
            }
            log.debug("Workspace SSE 连接释放 | userId={}", userId);
        };

        emitter.onCompletion(onDisconnect);
        emitter.onTimeout(() -> {
            log.info("Workspace SSE 超时断开 | userId={}", userId);
            emitter.complete();
            onDisconnect.run();
        });
        emitter.onError(ex -> {
            log.debug("Workspace SSE 网络错误 | userId={}", userId);
            onDisconnect.run();
        });

        log.info("Workspace SSE 新增连接 | userId={}, userTabCount={}", userId, userEmitters.size());
        return emitter;
    }

    public void pushToUser(Long userId, WorkspaceEvent event) {
        CopyOnWriteArraySet<SseEmitter> emitters = userConnections.get(userId);
        if (emitters == null || emitters.isEmpty()) {
            log.debug("用户无活跃 SSE 连接，跳过推送 | userId={}, eventType={}", userId, event.type());
            return;
        }

        String jsonData;
        try {
            jsonData = objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            log.error("SSE 推送事件序列化失败 | eventType={}", event.type(), e);
            return;
        }

        for (SseEmitter emitter : emitters) {
            try {
                synchronized (emitter) {
                    emitter.send(SseEmitter.event().name(event.type()).data(jsonData));
                }
            } catch (IOException e) {
                log.debug("SSE 推送失败，清理失效连接 | userId={}", userId);
                emitters.remove(emitter);
                try { emitter.complete(); } catch (Exception ignored) {}
            }
        }
    }

    private void sendHeartbeat() {
        if (userConnections.isEmpty()) {
            return;
        }
        userConnections.forEach((userId, emitters) -> {
            for (SseEmitter emitter : emitters) {
                try {
                    synchronized (emitter) {
                        emitter.send(SseEmitter.event().comment("heartbeat"));
                    }
                } catch (Exception e) {
                    log.debug("心跳发送失败，清理失效连接 | userId={}", userId);
                    emitters.remove(emitter);
                    try { emitter.complete(); } catch (Exception ignored) {}
                }
            }
        });
    }

    public int getActiveConnectionCount() {
        return userConnections.values().stream().mapToInt(CopyOnWriteArraySet::size).sum();
    }
}