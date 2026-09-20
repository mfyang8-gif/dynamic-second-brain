package com.yangmf.mini_nodepad.controller;

import com.yangmf.mini_nodepad.pojo.dto.BookChatDTO;
import com.yangmf.mini_nodepad.service.impl.ChatStreamService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

@Slf4j
@RestController
@RequestMapping("/api/v1/chat")
@RequiredArgsConstructor
@Tag(name = "流式对话", description = "SSE 流式对话接口")
public class ChatStreamController {

    private final ChatStreamService chatStreamService;

    @Value("${sse.timeout:300000}")
    private long sseTimeout;

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "流式对话（SSE）")
    public SseEmitter stream(@Valid @RequestBody BookChatDTO dto) {
        SseEmitter emitter = new SseEmitter(sseTimeout);

        Disposable disposable;
        try {
            disposable = chatStreamService.chatStream(dto)
                    .subscribe(
                            event -> {
                                try {
                                    emitter.send(
                                            SseEmitter.event()
                                                    .name(event.type())
                                                    .data(event.data())
                                    );
                                } catch (Exception e) {
                                    log.debug("SSE 发送失败，客户端可能已断开 | sessionId={}", dto.getSessionId());
                                    emitter.complete();
                                }
                            },
                            error -> {
                                log.error("Flux 流处理异常 | sessionId={}", dto.getSessionId(), error);
                                emitter.completeWithError(error);
                            },
                            emitter::complete
                    );
        } catch (Exception e) {
            log.error("创建流式对话失败 | sessionId={}", dto.getSessionId(), e);
            try {
                emitter.completeWithError(e);
            } catch (Exception ignored) {
            }
            return emitter;
        }

        Runnable onDisconnect = () -> {
            if (disposable != null && !disposable.isDisposed()) {
                disposable.dispose();
                log.debug("SSE 连接释放，已取消后端 LLM 任务 | sessionId={}", dto.getSessionId());
            }
        };

        emitter.onTimeout(() -> {
            log.warn("SSE 会话超时自动断开 | sessionId={}", dto.getSessionId());
            emitter.complete();
            onDisconnect.run();
        });

        emitter.onError(ex -> {
            log.debug("SSE 底层网络错误 | sessionId={}", dto.getSessionId());
            onDisconnect.run();
        });

        emitter.onCompletion(onDisconnect);
        return emitter;
    }
}