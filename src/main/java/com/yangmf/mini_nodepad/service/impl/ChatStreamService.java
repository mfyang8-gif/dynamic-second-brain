package com.yangmf.mini_nodepad.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yangmf.mini_nodepad.aiservice.GeneralAssistant;
import com.yangmf.mini_nodepad.aiservice.MainChatAssistant;
import com.yangmf.mini_nodepad.enums.AiProcessStatusEnum;
import com.yangmf.mini_nodepad.exception.BusinessException;
import com.yangmf.mini_nodepad.exception.ForbiddenException;
import com.yangmf.mini_nodepad.exception.MiniNotePadException;
import com.yangmf.mini_nodepad.exception.ResourceNotFoundException;
import com.yangmf.mini_nodepad.guard.ChatInputGuard;
import com.yangmf.mini_nodepad.mapper.PageMapper;
import com.yangmf.mini_nodepad.pojo.dto.BookChatDTO;
import com.yangmf.mini_nodepad.pojo.entity.Page;
import com.yangmf.mini_nodepad.pojo.vo.ChatSessionVO;
import com.yangmf.mini_nodepad.service.ChatConfigService;
import com.yangmf.mini_nodepad.service.ChatSessionService;
import com.yangmf.mini_nodepad.service.PageRagRetrievalService;
import dev.langchain4j.service.TokenStream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeoutException;

@Slf4j
@Service
public class ChatStreamService {

    private final MainChatAssistant mainChatAssistant;
    private final PageRagRetrievalService pageRagRetrievalService;
    private final ChatConfigService chatConfigService;
    private final ChatInputGuard chatInputGuard;
    private final ChatSessionService chatSessionService;
    private final GeneralAssistant generalAssistant;
    private final PageMapper pageMapper;
    private final ObjectMapper objectMapper;
    private final Executor aiTaskExecutor;

    @Value("${chat.title-max-length:50}")
    private int titleMaxLength;

    private static final String FIRST_SESSION_TITLE = "新对话";

    private static final String ERR_CODE_FORBIDDEN = "FORBIDDEN";
    private static final String ERR_CODE_NOT_FOUND = "NOT_FOUND";
    private static final String ERR_CODE_RAG_FAILURE = "RAG_FAILURE";
    private static final String ERR_CODE_LLM_TIMEOUT = "LLM_TIMEOUT";
    private static final String ERR_CODE_LLM_OVERLOADED = "LLM_OVERLOADED";
    private static final String ERR_CODE_LLM_FAILURE = "LLM_FAILURE";
    private static final String ERR_CODE_BAD_REQUEST = "BAD_REQUEST";
    private static final String ERR_CODE_SYSTEM = "SYSTEM_ERROR";

    public ChatStreamService(MainChatAssistant mainChatAssistant,
                             PageRagRetrievalService pageRagRetrievalService,
                             ChatConfigService chatConfigService,
                             ChatInputGuard chatInputGuard,
                             ChatSessionService chatSessionService,
                             GeneralAssistant generalAssistant,
                             PageMapper pageMapper,
                             ObjectMapper objectMapper,
                             @Qualifier("aiTaskExecutor") Executor aiTaskExecutor) {
        this.mainChatAssistant = mainChatAssistant;
        this.pageRagRetrievalService = pageRagRetrievalService;
        this.chatConfigService = chatConfigService;
        this.chatInputGuard = chatInputGuard;
        this.chatSessionService = chatSessionService;
        this.generalAssistant = generalAssistant;
        this.pageMapper = pageMapper;
        this.objectMapper = objectMapper;
        this.aiTaskExecutor = aiTaskExecutor;
    }

    public Flux<ChatStreamEvent> chatStream(BookChatDTO dto) {
        return Flux.create(sink -> Thread.startVirtualThread(() -> {
            try {
                sink.next(statusEvent("understanding", "正在理解您的问题..."));

                String sanitizedQuestion = chatInputGuard.sanitize(dto.getQuestion());
                if (sanitizedQuestion.isEmpty()) {
                    sink.next(errorEvent(ERR_CODE_BAD_REQUEST, "问题内容不能为空"));
                    sink.complete();
                    return;
                }

                String sanitizedCustomText = chatInputGuard.sanitizeCustomText(dto.getCustomText());
                String roleInstruction = chatConfigService.resolveRoleInstruction(dto.getRole(), sanitizedCustomText);
                String lengthConstraint = chatConfigService.resolveLengthConstraint(dto.getLength());

                String sessionId = dto.getSessionId();
                String bookId = chatSessionService.getBookIdBySessionId(sessionId);

                List<String> effectivePageIds = dto.getPageIds();
                if (effectivePageIds != null && !effectivePageIds.isEmpty()) {
                    List<Page> pages = pageMapper.selectBatchIds(effectivePageIds);
                    List<String> unreadyPageIds = pages.stream()
                            .filter(p -> p.getAiProcessStatus() == null || p.getAiProcessStatus() != AiProcessStatusEnum.SUCCESS)
                            .map(Page::getId)
                            .toList();
                    if (!unreadyPageIds.isEmpty()) {
                        log.info("Chat 过滤未就绪页面 | sessionId={}, unreadyPageIds={}", sessionId, unreadyPageIds);
                        sink.next(statusEvent("skipped",
                                "已跳过 " + unreadyPageIds.size() + " 个未完成 AI 处理的笔记"));
                    }
                    List<String> readyPageIds = pages.stream()
                            .filter(p -> p.getAiProcessStatus() != null && p.getAiProcessStatus() == AiProcessStatusEnum.SUCCESS)
                            .map(Page::getId)
                            .toList();
                    if (readyPageIds.isEmpty()) {
                        sink.next(errorEvent(ERR_CODE_BAD_REQUEST,
                                "所选笔记均未完成 AI 处理，无法进行检索"));
                        sink.complete();
                        return;
                    }
                    effectivePageIds = readyPageIds;
                }

                sink.next(statusEvent("retrieving", "正在检索知识库..."));

                PageRagRetrievalService.ProcessedResult ragResult;
                try {
                    ragResult = pageRagRetrievalService.process(
                            pageRagRetrievalService.retrieveChunks(sanitizedQuestion, bookId, effectivePageIds)
                    );
                } catch (BusinessException e) {
                    log.error("RAG 检索基础设施异常 | bookId={}", bookId, e);
                    sink.next(errorEvent(ERR_CODE_RAG_FAILURE, "知识检索服务暂时不可用，请稍后重试"));
                    sink.complete();
                    return;
                }

                String context;
                if (ragResult.hasResults()) {
                    int chunkCount = ragResult.rawChunks().size();
                    sink.next(statusEvent("retrieved", "检索到 " + chunkCount + " 个相关片段"));
                    context = ragResult.contextString();
                } else {
                    sink.next(statusEvent("retrieved", "未检索到相关内容"));
                    context = "无相关检索结果";
                    log.info("RAG 检索无命中 | bookId={}, sessionId={}", bookId, sessionId);
                }

                sink.next(statusEvent("thinking", "正在思考中..."));

                TokenStream tokenStream = mainChatAssistant.chat(
                        sessionId, roleInstruction, lengthConstraint, context, sanitizedQuestion
                );

                StringBuilder fullResponse = new StringBuilder();

                tokenStream
                        .onPartialResponse(token -> {
                            if (!sink.isCancelled()) {
                                fullResponse.append(token);
                                sink.next(messageEvent(token));
                            }
                        })
                        .onCompleteResponse(response -> {
                            try {
                                if (sink.isCancelled()) {
                                    log.warn("流式对话被客户端中止 | sessionId={}", sessionId);
                                    return;
                                }
                                List<String> sourcePageIds = ragResult.sourcePageIds();
                                sink.next(doneEvent(sourcePageIds));
                                persistMessages(sessionId, sanitizedQuestion, fullResponse.toString(), sourcePageIds);
                            } catch (Exception e) {
                                log.error("流完成后处理异常 | sessionId={}", sessionId, e);
                            } finally {
                                sink.complete();
                            }
                        })
                        .onError(error -> {
                            if (!sink.isCancelled()) {
                                handleLlmError(sessionId, error, sink);
                            }
                        })
                        .start();

            } catch (ForbiddenException e) {
                log.warn("对话权限校验失败 | sessionId={}, msg={}", dto.getSessionId(), e.getMessage());
                sink.next(errorEvent(ERR_CODE_FORBIDDEN, e.getMessage()));
                sink.complete();
            } catch (ResourceNotFoundException e) {
                log.warn("对话资源不存在 | sessionId={}, msg={}", dto.getSessionId(), e.getMessage());
                sink.next(errorEvent(ERR_CODE_NOT_FOUND, e.getMessage()));
                sink.complete();
            } catch (MiniNotePadException e) {
                log.warn("对话编排业务异常 | code={}, msg={}", e.getStatus(), e.getMessage());
                sink.next(errorEvent(ERR_CODE_BAD_REQUEST, e.getMessage()));
                sink.complete();
            } catch (Exception e) {
                log.error("对话编排未知异常 | sessionId={}", dto.getSessionId(), e);
                sink.next(errorEvent(ERR_CODE_SYSTEM, "系统异常，请稍后重试"));
                sink.complete();
            }
        }));
    }

    // ==================== 错误处理 ====================

    private void handleLlmError(String sessionId, Throwable error, reactor.core.publisher.FluxSink<ChatStreamEvent> sink) {
        String code;
        String message;

        if (isTimeoutError(error)) {
            code = ERR_CODE_LLM_TIMEOUT;
            message = "AI 响应超时，请稍后重试或缩短问题长度";
            log.warn("LLM 超时 | sessionId={}", sessionId);
        } else if (isOverloadedError(error)) {
            code = ERR_CODE_LLM_OVERLOADED;
            message = "AI 模型当前繁忙，请稍后再试";
            log.warn("LLM 过载/限流 | sessionId={}", sessionId);
        } else {
            code = ERR_CODE_LLM_FAILURE;
            message = "AI 生成回复时发生错误，请稍后重试";
            log.error("LLM 流式响应异常 | sessionId={}", sessionId, error);
        }

        sink.next(errorEvent(code, message));
        sink.complete();
    }

    private boolean isTimeoutError(Throwable error) {
        return walkCauses(error, e ->
                e instanceof SocketTimeoutException
                        || e instanceof TimeoutException
                        || (e.getMessage() != null && e.getMessage().toLowerCase().contains("timeout"))
        );
    }

    private boolean isOverloadedError(Throwable error) {
        return walkCauses(error, e -> {
            String msg = e.getMessage();
            if (msg == null) {
                return false;
            }
            String lower = msg.toLowerCase();
            return lower.contains("429") || lower.contains("rate limit")
                    || lower.contains("overloaded") || lower.contains("too many requests");
        });
    }

    private boolean walkCauses(Throwable error, java.util.function.Predicate<Throwable> predicate) {
        Throwable current = error;
        while (current != null) {
            if (predicate.test(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    // ==================== 持久化 ====================

    private void persistMessages(String sessionId, String question, String answer, List<String> sourcePageIds) {
        aiTaskExecutor.execute(() -> {
            try {
                chatSessionService.saveMessage(sessionId, "user", question, List.of());
            } catch (Exception e) {
                log.error("用户消息持久化失败 | sessionId={}", sessionId, e);
            }
            try {
                chatSessionService.saveMessage(sessionId, "assistant", answer, sourcePageIds);
            } catch (Exception e) {
                log.error("AI 回复持久化失败 | sessionId={}", sessionId, e);
            }
            tryUpdateSessionTitle(sessionId, question);
        });
    }

    // ==================== 标题自动生成 ====================

    private void tryUpdateSessionTitle(String sessionId, String userQuestion) {
        try {
            ChatSessionVO session = chatSessionService.getSessionById(sessionId);
            if (FIRST_SESSION_TITLE.equals(session.getTitle())) {
                String title = generalAssistant.generateTitle(userQuestion);
                if (title != null && !title.isBlank()) {
                    if (title.length() > titleMaxLength) {
                        title = title.substring(0, titleMaxLength);
                    }
                    chatSessionService.updateTitle(sessionId, title);
                    log.info("会话标题自动更新 | sessionId={}, title={}", sessionId, title);
                }
            }
        } catch (Exception e) {
            log.warn("自动更新会话标题失败 | sessionId={}", sessionId, e);
        }
    }

    // ==================== 事件构建 ====================

    private ChatStreamEvent statusEvent(String phase, String message) {
        try {
            return new ChatStreamEvent("status", objectMapper.writeValueAsString(
                    Map.of("phase", phase, "message", message)
            ));
        } catch (JsonProcessingException e) {
            return new ChatStreamEvent("status", "{\"phase\":\"" + phase + "\",\"message\":\"" + message + "\"}");
        }
    }

    private ChatStreamEvent messageEvent(String token) {
        try {
            return new ChatStreamEvent("message", objectMapper.writeValueAsString(token));
        } catch (JsonProcessingException e) {
            return new ChatStreamEvent("message", "\"" + token.replace("\"", "\\\"") + "\"");
        }
    }

    private ChatStreamEvent doneEvent(List<String> sourcePageIds) {
        try {
            return new ChatStreamEvent("done", objectMapper.writeValueAsString(Map.of("sourcePageIds", sourcePageIds)));
        } catch (JsonProcessingException e) {
            return new ChatStreamEvent("done", "{\"sourcePageIds\":[]}");
        }
    }

    private ChatStreamEvent errorEvent(String code, String message) {
        try {
            return new ChatStreamEvent("error", objectMapper.writeValueAsString(
                    Map.of("code", code, "message", message)
            ));
        } catch (JsonProcessingException e) {
            return new ChatStreamEvent("error", "{\"code\":\"SYSTEM_ERROR\",\"message\":\"未知错误\"}");
        }
    }

    public record ChatStreamEvent(String type, String data) {
    }
}