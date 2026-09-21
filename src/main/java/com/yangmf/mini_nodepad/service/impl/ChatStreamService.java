package com.yangmf.mini_nodepad.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yangmf.mini_nodepad.ai.aiservice.GeneralAssistant;
import com.yangmf.mini_nodepad.ai.aiservice.MainChatAssistant;
import com.yangmf.mini_nodepad.ai.component.*;
import com.yangmf.mini_nodepad.ai.rag.PageRagRetrievalService;
import com.yangmf.mini_nodepad.enums.AiProcessStatusEnum;
import com.yangmf.mini_nodepad.enums.IntentType;
import com.yangmf.mini_nodepad.exception.BusinessException;
import com.yangmf.mini_nodepad.exception.ForbiddenException;
import com.yangmf.mini_nodepad.exception.MiniNotePadException;
import com.yangmf.mini_nodepad.exception.ResourceNotFoundException;
import com.yangmf.mini_nodepad.mapper.PageMapper;
import com.yangmf.mini_nodepad.pojo.dto.BookChatDTO;
import com.yangmf.mini_nodepad.pojo.entity.Page;
import com.yangmf.mini_nodepad.pojo.vo.ChatSessionVO;
import com.yangmf.mini_nodepad.service.ChatSessionService;
import com.yangmf.mini_nodepad.utils.ThinkingTagFilter;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.service.TokenStream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.net.SocketTimeoutException;
import java.util.LinkedHashMap;
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
    private final QueryRewriteService queryRewriteService;
    private final IntentRouterService intentRouterService;
    private final ChatSummaryService chatSummaryService;
    private final ChatMemoryProvider chatMemoryProvider;

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
                             @Qualifier("aiTaskExecutor") Executor aiTaskExecutor,
                             QueryRewriteService queryRewriteService,
                             IntentRouterService intentRouterService,
                             ChatSummaryService chatSummaryService,
                             ChatMemoryProvider chatMemoryProvider) {
        this.mainChatAssistant = mainChatAssistant;
        this.pageRagRetrievalService = pageRagRetrievalService;
        this.chatConfigService = chatConfigService;
        this.chatInputGuard = chatInputGuard;
        this.chatSessionService = chatSessionService;
        this.generalAssistant = generalAssistant;
        this.pageMapper = pageMapper;
        this.objectMapper = objectMapper;
        this.aiTaskExecutor = aiTaskExecutor;
        this.queryRewriteService = queryRewriteService;
        this.intentRouterService = intentRouterService;
        this.chatSummaryService = chatSummaryService;
        this.chatMemoryProvider = chatMemoryProvider;
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

                String sessionId = dto.getSessionId();

                IntentType intent = intentRouterService.classify(sanitizedQuestion);
                log.info("意图路由结果 | sessionId={}, intent={}", sessionId, intent);

                if (intent == IntentType.CHITCHAT || intent == IntentType.META_QUESTION) {
                    handleNonRagChat(sink, sessionId, sanitizedQuestion);
                    return;
                }

                handleRagChat(sink, dto, sessionId, sanitizedQuestion);

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

    // ==================== 非 RAG 路径（闲聊/元问题） ====================

    private void handleNonRagChat(reactor.core.publisher.FluxSink<ChatStreamEvent> sink,
                                  String sessionId, String question) {
        sink.next(statusEvent("thinking", "正在思考中..."));

        String historySummary = chatSummaryService.getSummary(sessionId);
        TokenStream tokenStream = mainChatAssistant.chat(sessionId, historySummary, question);
        StringBuilder fullResponse = new StringBuilder();
        ThinkingTagFilter tagFilter = new ThinkingTagFilter();

        tokenStream
                .onPartialResponse(token -> {
                    if (!sink.isCancelled()) {
                        fullResponse.append(token);
                        String visible = tagFilter.filter(token);
                        if (!visible.isEmpty()) {
                            sink.next(messageEvent(visible));
                        }
                    }
                })
                .onCompleteResponse(response -> {
                    try {
                        if (sink.isCancelled()) {
                            log.warn("流式对话被客户端中止 | sessionId={}", sessionId);
                            return;
                        }
                        sink.next(doneEventEmpty());
                        persistMessages(sessionId, question, fullResponse.toString(), List.of());
                        chatSummaryService.triggerAsyncSummarization(sessionId);
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
    }

    // ==================== RAG 路径（知识查询） ====================

    private void handleRagChat(reactor.core.publisher.FluxSink<ChatStreamEvent> sink,
                               BookChatDTO dto, String sessionId, String sanitizedQuestion) {
        String sanitizedCustomText = chatInputGuard.sanitizeCustomText(dto.getCustomText());
        String roleInstruction = chatConfigService.resolveRoleInstruction(dto.getRole(), sanitizedCustomText);
        String lengthConstraint = chatConfigService.resolveLengthConstraint(dto.getLength());

        String bookId = chatSessionService.getBookIdBySessionId(sessionId);
        String historySummary = chatSummaryService.getSummary(sessionId);

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
        final List<String> searchPageIds = effectivePageIds;

        sink.next(statusEvent("rewriting", "正在优化检索查询..."));

        String conversationContext = buildConversationContext(sessionId);
        QueryRewriteService.RewrittenQuery rewritten =
                queryRewriteService.rewrite(sanitizedQuestion, conversationContext);

        sink.next(statusEvent("retrieving", "正在检索知识库..."));

        PageRagRetrievalService.ProcessedResult ragResult;
        try {
            List<PageRagRetrievalService.RagChunk> allChunks;

            if (rewritten.decomposed()) {
                log.info("Query 已拆分为子查询 | count={}, queries={}",
                        rewritten.searchQueries().size(), rewritten.searchQueries());

                allChunks = rewritten.searchQueries().stream()
                        .flatMap(subQuery -> pageRagRetrievalService
                                .retrieveChunks(subQuery, bookId, searchPageIds, 3)
                                .stream())
                        .distinct()
                        .toList();

                sink.next(statusEvent("retrieved",
                        "问题已拆分为 " + rewritten.searchQueries().size()
                                + " 个子查询，合并检索到 " + allChunks.size() + " 个片段"));
            } else {
                allChunks = pageRagRetrievalService.retrieveChunks(
                        rewritten.primaryQuery(), bookId, searchPageIds);
            }

            ragResult = pageRagRetrievalService.process(allChunks);
        } catch (BusinessException e) {
            log.error("RAG 检索基础设施异常 | bookId={}", bookId, e);
            sink.next(errorEvent(ERR_CODE_RAG_FAILURE, "知识检索服务暂时不可用，请稍后重试"));
            sink.complete();
            return;
        }

        String context;
        if (ragResult.hasResults()) {
            int chunkCount = ragResult.rawChunks().size();
            if (!rewritten.decomposed()) {
                sink.next(statusEvent("retrieved", "检索到 " + chunkCount + " 个相关片段"));
            }
            context = ragResult.contextString();
        } else {
            sink.next(statusEvent("retrieved", "未检索到相关内容"));
            context = "无相关检索结果";
            log.info("RAG 检索无命中 | bookId={}, sessionId={}", bookId, sessionId);
        }

        sink.next(statusEvent("thinking", "正在思考中..."));

        TokenStream tokenStream = mainChatAssistant.chat(
                sessionId, roleInstruction, lengthConstraint, context,
                historySummary, sanitizedQuestion
        );

        StringBuilder fullResponse = new StringBuilder();
        ThinkingTagFilter tagFilter = new ThinkingTagFilter();

        tokenStream
                .onPartialResponse(token -> {
                    if (!sink.isCancelled()) {
                        fullResponse.append(token);
                        String visible = tagFilter.filter(token);
                        if (!visible.isEmpty()) {
                            sink.next(messageEvent(visible));
                        }
                    }
                })
                .onCompleteResponse(response -> {
                    try {
                        if (sink.isCancelled()) {
                            log.warn("流式对话被客户端中止 | sessionId={}", sessionId);
                            return;
                        }
                        List<String> sourcePageIds = ragResult.sourcePageIds();
                        sink.next(doneEvent(ragResult));
                        persistMessages(sessionId, sanitizedQuestion, fullResponse.toString(), sourcePageIds);
                        chatSummaryService.triggerAsyncSummarization(sessionId);
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
    }

    // ==================== 多轮对话上下文提取（供 QueryRewrite 指代消解） ====================

    private String buildConversationContext(String sessionId) {
        try {
            List<ChatMessage> messages = chatMemoryProvider.get(sessionId).messages();
            if (messages == null || messages.isEmpty()) {
                return "";
            }

            int size = messages.size();
            int keep = Math.min(6, size);
            List<ChatMessage> recentMessages = messages.subList(size - keep, size);

            StringBuilder sb = new StringBuilder();
            for (ChatMessage msg : recentMessages) {
                if (msg.type() == ChatMessageType.USER) {
                    UserMessage um = (UserMessage) msg;
                    sb.append("用户: ").append(um.singleText()).append("\n");
                } else if (msg.type() == ChatMessageType.AI) {
                    AiMessage am = (AiMessage) msg;
                    String text = am.text();
                    if (text != null && text.length() > 150) {
                        text = text.substring(0, 150) + "...";
                    }
                    sb.append("助手: ").append(text != null ? text : "").append("\n");
                }
            }
            return sb.toString().trim();
        } catch (Exception e) {
            log.warn("构建改写上下文失败，降级为无上下文 | sessionId={}", sessionId, e);
            return "";
        }
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

    private ChatStreamEvent doneEvent(PageRagRetrievalService.ProcessedResult ragResult) {
        try {
            List<Map<String, Object>> refs = ragResult.sourceReferences().stream()
                    .map(r -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("index", r.index());
                        m.put("title", r.title() != null ? r.title() : "");
                        m.put("pageId", r.pageId() != null ? r.pageId() : "");
                        m.put("chunkIndex", r.chunkIndex());
                        m.put("textSnippet", r.textSnippet());
                        return m;
                    })
                    .toList();
            return new ChatStreamEvent("done", objectMapper.writeValueAsString(
                    Map.of("sourcePageIds", ragResult.sourcePageIds(), "sourceReferences", refs)
            ));
        } catch (JsonProcessingException e) {
            return new ChatStreamEvent("done", "{\"sourcePageIds\":[],\"sourceReferences\":[]}");
        }
    }

    private ChatStreamEvent doneEventEmpty() {
        try {
            return new ChatStreamEvent("done", objectMapper.writeValueAsString(
                    Map.of("sourcePageIds", List.of(), "sourceReferences", List.of())
            ));
        } catch (JsonProcessingException e) {
            return new ChatStreamEvent("done", "{\"sourcePageIds\":[],\"sourceReferences\":[]}");
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