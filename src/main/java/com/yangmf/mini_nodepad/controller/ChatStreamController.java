package com.yangmf.mini_nodepad.controller;

import com.yangmf.mini_nodepad.pojo.dto.BookChatDTO;

import com.yangmf.mini_nodepad.service.impl.ChatStreamService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
    private static final long SSE_TIMEOUT = 300_000L; // 5分钟超时

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "流式对话（SSE）")
    public SseEmitter stream(@Valid @RequestBody BookChatDTO dto) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);

        // 【关键修复 1】持有订阅句柄，用于在异常断开时强杀 LLM 流，防止资源泄露
        Disposable disposable = chatStreamService.chatStream(dto)
                .subscribe(
                        event -> {
                            try {
                                // 【关键修复 2】直接输出原生 JSON 字符串，不做无效的 JsonNode 反序列化
                                emitter.send(
                                        SseEmitter.event()
                                                .name(event.type())
                                                .data(event.data()) 
                                );
                            } catch (Exception e) {
                                log.debug("SSE 客户端主动断开或网络异常 | sessionId={}", dto.getSessionId());
                                emitter.complete();
                            }
                        },
                        error -> {
                            log.error("Flux 流处理异常 | sessionId={}", dto.getSessionId(), error);
                            emitter.completeWithError(error);
                        },
                        emitter::complete
                );

        // 【关键修复 3】无论超时、出错还是完成，都必须解除 Flux 订阅，通知底层的 sink.isCancelled() 变为 true
        Runnable onDisconnect = () -> {
            if (!disposable.isDisposed()) {
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
            log.debug("SSE 会话发生底层网络错误 | sessionId={}", dto.getSessionId());
            onDisconnect.run();
        });
        
        emitter.onCompletion(onDisconnect);
        return emitter;
    }
}