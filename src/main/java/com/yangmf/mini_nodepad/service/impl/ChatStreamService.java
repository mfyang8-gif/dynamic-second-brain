package com.yangmf.mini_nodepad.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yangmf.mini_nodepad.ai.aiservice.GeneralAssistant;
import com.yangmf.mini_nodepad.ai.aiservice.MainChatAssistant;
import com.yangmf.mini_nodepad.ai.component.*;
import com.yangmf.mini_nodepad.ai.rag.DualPathRetrievalService;
import com.yangmf.mini_nodepad.ai.rag.PageRagRetrievalService;
import com.yangmf.mini_nodepad.ai.rag.SourceReference;
import com.yangmf.mini_nodepad.context.BaseContext;
import com.yangmf.mini_nodepad.enums.AiProcessStatusEnum;
import com.yangmf.mini_nodepad.enums.SemanticRuleType;
import com.yangmf.mini_nodepad.enums.UserIntent;
import com.yangmf.mini_nodepad.exception.BusinessException;
import com.yangmf.mini_nodepad.exception.ForbiddenException;
import com.yangmf.mini_nodepad.exception.MiniNotePadException;
import com.yangmf.mini_nodepad.exception.ResourceNotFoundException;
import com.yangmf.mini_nodepad.mapper.PageMapper;
import com.yangmf.mini_nodepad.mq.GraphitiScanProducer;
import com.yangmf.mini_nodepad.mq.SemanticScanProducer;
import com.yangmf.mini_nodepad.pojo.dto.BookChatDTO;
import com.yangmf.mini_nodepad.pojo.entity.Page;
import com.yangmf.mini_nodepad.pojo.vo.ChatSessionVO;
import com.yangmf.mini_nodepad.service.ChatSessionService;
import com.yangmf.mini_nodepad.service.SemanticMemoryService;
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
import reactor.core.publisher.FluxSink;

import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 流式对话编排。
 *
 * <p><b>2026-10-12 重构（用户终版裁决）</b>：把「串行漏斗 + 双层判定 + 单段作答」
 * 换成「<b>恒并行双路 + 两段流式</b>」。核心变化：
 * <ol>
 *   <li><b>双路并发</b>：本地 Qdrant 与全网 MCP 同时发起、互不等待。旧的"本地命中就阻断联网"
 *       是时效劫持 / 局部满足 / 实体混淆三类盲区的共同根因，现在结构上不存在了。</li>
 *   <li><b>两段流式</b>：本地路先回来（~1s）就先开 Pass1 作答，答案首字从 20~35s 降到 1~2s；
 *       联网路回来（~20s）再开 Pass2 自然续写。两段共用同一条 SSE 流、同一个前端气泡——
 *       {@code message} 事件本就是 delta 累加，<b>契约零变更</b>，{@code done} 只在最后发一次。</li>
 *   <li><b>判卷器彻底删除</b>：不再有"上游替用户做取舍"的环节，本地与全网谁更可信
 *       交由主模型在提示词约束下交叉裁决（见 CoreChatPrompt 的【来源权威性裁决】）。</li>
 * </ol>
 *
 * <p>关于"给正在生成的流注入上下文"——机制上做不到（自回归生成中 messages 在请求发出那一刻已固定），
 * 所以 Pass2 是一次<b>新的生成</b>，靠"重放第一段输出 + 提示词要求接着写、不重复"实现无缝续写。
 * 用户与前端感知到的是一条连续的流。
 */
@Slf4j
@Service
public class ChatStreamService {

    private final MainChatAssistant mainChatAssistant;
    private final ChatConfigService chatConfigService;
    private final ChatInputGuard chatInputGuard;
    private final ChatSessionService chatSessionService;
    private final GeneralAssistant generalAssistant;
    private final PageMapper pageMapper;
    private final ObjectMapper objectMapper;
    private final Executor aiTaskExecutor;
    private final QueryRewriteService queryRewriteService;
    private final IntentRoutingService intentRoutingService;
    private final ChatSummaryService chatSummaryService;
    private final ChatMemoryProvider chatMemoryProvider;
    private final SuggestionService suggestionService;
    private final RegenerateInstructionStore regenerateInstructionStore;
    private final SemanticMemoryService semanticMemoryService;
    private final PromptSanitizer promptSanitizer;
    private final SemanticScanProducer semanticScanProducer;
    private final GraphitiScanProducer graphitiScanProducer;         // 2026-10-13：B1 写入链入口（含高危快通道）
    private final DualPathRetrievalService dualPathRetrievalService; // 2026-10-12：双路并发编排
    private final WebAugmentService webAugmentService;               // 2026-10-12：网页来源组装/引用生成
    private final RetrievalMetrics retrievalMetrics;                 // 2026-10-12：降级可观测性

    @Value("${chat.title-max-length:50}")
    private int titleMaxLength;

    /** 双路总开关：false 时只走本地、单段作答（回滚用，等价于改造前的行为） */
    @Value("${rag.dual-path.enabled:true}")
    private boolean dualPathEnabled;

    /** 本地路就绪等待上限：超时不开 Pass1（不让本地拖慢首字），联网路继续跑 */
    @Value("${rag.dual-path.local-ready-timeout-ms:4000}")
    private long localReadyTimeoutMs;

    /** 联网路总预算：超过就放弃等联网，直接收尾 */
    @Value("${rag.dual-path.web-budget-ms:30000}")
    private long webBudgetMs;

    /**
     * 开 Pass1 的余弦底线（真实余弦 0~1）。
     * ⚠️ 必须作用在纯稠密通道的分数上 —— 主检索链路的 RRF 分数（典型 0.01~0.03）与余弦不可比。
     */
    @Value("${rag.dual-path.pass1-cosine-gate:0.28}")
    private float pass1CosineGate;

    /** Pass1 软预算（字数）：靠提示词约束，不是硬截断 */
    @Value("${rag.dual-path.pass1-soft-budget-chars:400}")
    private int pass1SoftBudgetChars;

    /** 单次 Pass 的兜底超时：防 LLM 流悬挂把虚拟线程永久占住 */
    @Value("${rag.dual-path.pass-timeout-ms:180000}")
    private long passTimeoutMs;

    private static final String FIRST_SESSION_TITLE = "新对话";

    private static final String ERR_CODE_FORBIDDEN = "FORBIDDEN";
    private static final String ERR_CODE_NOT_FOUND = "NOT_FOUND";
    private static final String ERR_CODE_RAG_FAILURE = "RAG_FAILURE";
    private static final String ERR_CODE_LLM_TIMEOUT = "LLM_TIMEOUT";
    private static final String ERR_CODE_LLM_OVERLOADED = "LLM_OVERLOADED";
    private static final String ERR_CODE_LLM_FAILURE = "LLM_FAILURE";
    private static final String ERR_CODE_BAD_REQUEST = "BAD_REQUEST";
    private static final String ERR_CODE_SYSTEM = "SYSTEM_ERROR";

    /** notice 事件的 code：联网路降级 */
    private static final String NOTICE_WEB_FALLBACK = "WEB_FALLBACK";

    public ChatStreamService(MainChatAssistant mainChatAssistant,
                             ChatConfigService chatConfigService,
                             ChatInputGuard chatInputGuard,
                             ChatSessionService chatSessionService,
                             GeneralAssistant generalAssistant,
                             PageMapper pageMapper,
                             ObjectMapper objectMapper,
                             @Qualifier("aiTaskExecutor") Executor aiTaskExecutor,
                             QueryRewriteService queryRewriteService,
                             IntentRoutingService intentRoutingService,
                             ChatSummaryService chatSummaryService,
                             ChatMemoryProvider chatMemoryProvider,
                             SuggestionService suggestionService,
                             RegenerateInstructionStore regenerateInstructionStore,
                             SemanticMemoryService semanticMemoryService,
                             PromptSanitizer promptSanitizer,
                             SemanticScanProducer semanticScanProducer,
                             GraphitiScanProducer graphitiScanProducer,
                             DualPathRetrievalService dualPathRetrievalService,
                             WebAugmentService webAugmentService,
                             RetrievalMetrics retrievalMetrics) {
        this.mainChatAssistant = mainChatAssistant;
        this.chatConfigService = chatConfigService;
        this.chatInputGuard = chatInputGuard;
        this.chatSessionService = chatSessionService;
        this.generalAssistant = generalAssistant;
        this.pageMapper = pageMapper;
        this.objectMapper = objectMapper;
        this.aiTaskExecutor = aiTaskExecutor;
        this.queryRewriteService = queryRewriteService;
        this.intentRoutingService = intentRoutingService;
        this.chatSummaryService = chatSummaryService;
        this.chatMemoryProvider = chatMemoryProvider;
        this.suggestionService = suggestionService;
        this.regenerateInstructionStore = regenerateInstructionStore;
        this.semanticMemoryService = semanticMemoryService;
        this.promptSanitizer = promptSanitizer;
        this.semanticScanProducer = semanticScanProducer;
        this.graphitiScanProducer = graphitiScanProducer;
        this.dualPathRetrievalService = dualPathRetrievalService;
        this.webAugmentService = webAugmentService;
        this.retrievalMetrics = retrievalMetrics;
    }

    public Flux<ChatStreamEvent> chatStream(BookChatDTO dto) {
        // BaseContext 基于 Tomcat 请求线程的 ThreadLocal；切到虚拟线程/回调线程后取不到，
        // 必须在开启虚拟线程前于请求线程捕获 userId，再在各工作线程内重新 bind、finally remove 防线程池泄漏
        final Long userId = BaseContext.getCurrentId();
        return Flux.create(sink -> Thread.startVirtualThread(() -> {
            BaseContext.setCurrentId(userId);
            try {
                String sanitizedQuestion = chatInputGuard.sanitize(dto.getQuestion());
                if (sanitizedQuestion.isEmpty()) {
                    sink.next(errorEvent(ERR_CODE_BAD_REQUEST, "问题内容不能为空"));
                    sink.complete();
                    return;
                }

                String sessionId = dto.getSessionId();

                // 重新生成上下文消费：仅携带有效 token 的 regenerate 重发请求命中。
                // 一次性 token 的消耗结果同时决定两件事：
                //   ① 注入给模型的临时 System Prompt 片段（可为空串）；
                //   ② 落库时是否跳过 user 行 Insert —— 见 RegenerateContext 的说明。
                RegenerateContext regen = resolveRegenerateContext(dto.getRegenerateToken());

                // ===== 意图路由中枢：流式管线第一个业务节点，向前端透明下发思考链路 =====
                // 前端按 MD3 / Swiss / Editorial 三套主题渲染纯文案，不挂 per-phase 图标
                sink.next(statusEvent("analyzing_intent", "正在分析意图..."));
                // 路由注入上下文：孤立单句会把「再给我推荐几个学习方向」这类追问误判成闲聊，实测复现
                UserIntent intent = intentRoutingService.determineIntent(
                        sanitizedQuestion, buildConversationContext(sessionId));
                log.info("意图路由结果 | sessionId={}, intent={}", sessionId, intent);

                switch (intent) {
                    case GENERAL_CHAT -> {
                        // 近期原文注入：闲聊模型获得追问理解能力
                        handleNonRagChat(sink, sessionId, sanitizedQuestion, userId, regen,
                                buildConversationContext(sessionId));
                        return;
                    }
                    case EPISODIC_MEMORY -> {
                        sink.next(statusEvent("retrieving_memory", "正在回溯长期记忆..."));
                        // 用户 2026-10-12 裁决：记忆检索 gap 本轮不修，维持 handleNonRagChat 现状
                        handleNonRagChat(sink, sessionId, sanitizedQuestion, userId, regen,
                                buildConversationContext(sessionId, 10));
                        return;
                    }
                    case HYBRID -> {
                        sink.next(statusEvent("retrieving_memory", "正在回溯长期记忆..."));
                        handleDualPathChat(sink, dto, sessionId, sanitizedQuestion, userId, regen);
                        return;
                    }
                    // 2026-10-12：WEB_SEARCH 不再单独短路 —— 双路并发下"先查本地"没有成本
                    // （本地 ~1s 就回来，还顺带能给出笔记里的相关背景），与 KNOWLEDGE_SEARCH 同路径。
                    case KNOWLEDGE_SEARCH, WEB_SEARCH ->
                            handleDualPathChat(sink, dto, sessionId, sanitizedQuestion, userId, regen);
                }

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
            } finally {
                BaseContext.removeCurrentId();
            }
        }));
    }

    // ==================== 非 RAG 路径（闲聊/元问题） ====================

    /**
     * 非 RAG 路径（GENERAL_CHAT / EPISODIC_MEMORY 共用）。
     * 双路重构【不触碰】这条分支：闲聊与记忆回溯都不联网、不并发检索。
     */
    private void handleNonRagChat(FluxSink<ChatStreamEvent> sink,
                                  String sessionId, String question, Long userId, RegenerateContext regen,
                                  String recentContext) {
        sink.next(statusEvent("thinking", "正在思考中..."));
        String historySummary = chatSummaryService.getSummary(sessionId);
        String userGlobalMemory = buildUserMemoryBlock(userId);
        TokenStream tokenStream = mainChatAssistant.chat(sessionId, historySummary, recentContext,
                regen.instruction(), userGlobalMemory, question);
        StringBuilder fullResponse = new StringBuilder();
        ThinkingTagFilter tagFilter = new ThinkingTagFilter();
        AtomicBoolean aborted = new AtomicBoolean(false);

        tokenStream
                .onPartialResponse(token -> {
                    if (sink.isCancelled()) {
                        if (aborted.compareAndSet(false, true)) {
                            log.info("用户主动中断闲聊，硬中断 LLM 流 | sessionId={}, partialLength={}",
                                    sessionId, fullResponse.length());
                            if (!fullResponse.isEmpty()) {
                                persistMessages(sessionId, question,
                                        fullResponse + "\n\n*[用户已中断回答]*", List.of(), List.of(), userId,
                                        regen.skipUserMessage());
                            }
                            throw new RuntimeException("CLIENT_ABORTED");
                        }
                        return;
                    }
                    fullResponse.append(token);
                    String visible = tagFilter.filter(token);
                    if (!visible.isEmpty()) {
                        sink.next(messageEvent(visible));
                    }
                })
                .onCompleteResponse(response -> {
                    if (aborted.get()) {
                        return;
                    }
                    try {
                        if (sink.isCancelled()) {
                            log.warn("流式对话被客户端中止 | sessionId={}", sessionId);
                            return;
                        }
                        sink.next(doneEventEmpty());
                        CompletableFuture<String> assistantRowId =
                                persistMessages(sessionId, question, fullResponse.toString(), List.of(), List.of(),
                                        userId, regen.skipUserMessage());
                        chatSummaryService.triggerAsyncSummarization(sessionId);
                        // 闲聊/元问题同样要出「猜你想问」：推荐只依赖「问题 + 答案」，与有没有走检索无关。
                        // 引用来源仍为空数组：本轮确实没有检索。
                        // ⚠️ 关流动作交给 triggerSuggestionAsync（它在自己的 finally 里 complete），
                        //    所以下面【不能】再 sink.complete()。
                        triggerSuggestionAsync(sink, sessionId, question, fullResponse.toString(), assistantRowId);
                    } catch (Exception e) {
                        log.error("流完成后处理异常 | sessionId={}", sessionId, e);
                        sink.complete();
                    }
                })
                .onError(error -> {
                    if (aborted.get() || "CLIENT_ABORTED".equals(error.getMessage())) {
                        log.debug("LLM HTTP 流已通过异常被强行斩断 | sessionId={}", sessionId);
                        return;
                    }
                    if (!sink.isCancelled()) {
                        handleLlmError(sessionId, error, sink);
                    }
                })
                .start();
    }

    // ==================== 双路并发 + 两段流式（知识查询） ====================

    /**
     * 双路并发 + 两段流式的完整编排。时序：
     * <pre>
     *   rewriting → retrieving → web_searching → retrieved
     *     → Pass1（本地先答，~1.5s 出首字）
     *     → 挂起等联网（~20s）
     *     → Pass2（自然续写，携带第一段输出确保不重复）
     *     → done（两路引用合并落库）
     * </pre>
     *
     * <p>退化路径（全部实现，不是可选项）：
     * <ul>
     *   <li>本地无料 / 余弦低于门槛 → 不开 Pass1（否则就是拿模型自身知识先编一版、20s 后再推翻）</li>
     *   <li>联网失败（超时 / 全被防护丢弃）→ 发 notice，Pass1 正常收尾，不开 Pass2</li>
     *   <li>本地失败 → 只等联网，单段作答</li>
     *   <li>两路全失败 → 纯模型兜底 + 声明（{@link #NO_SOURCE_CONTEXT}）</li>
     * </ul>
     */
    private void handleDualPathChat(FluxSink<ChatStreamEvent> sink,
                                    BookChatDTO dto, String sessionId, String sanitizedQuestion, Long userId,
                                    RegenerateContext regen) {
        String sanitizedCustomText = chatInputGuard.sanitizeCustomText(dto.getCustomText());
        String roleInstruction = chatConfigService.resolveRoleInstruction(dto.getRole(), sanitizedCustomText);
        String lengthConstraint = chatConfigService.resolveLengthConstraint(dto.getLength());

        String bookId = chatSessionService.getBookIdBySessionId(sessionId);
        String historySummary = chatSummaryService.getSummary(sessionId);

        // ---- 笔记范围就绪过滤（原逻辑原样保留）----
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
                sink.next(errorEvent(ERR_CODE_BAD_REQUEST, "所选笔记均未完成 AI 处理，无法进行检索"));
                sink.complete();
                return;
            }
            effectivePageIds = readyPageIds;
        }
        final List<String> searchPageIds = effectivePageIds;

        // ---- 查询改写（本地与联网共用同一份产物）----
        sink.next(statusEvent("rewriting", "正在优化检索查询..."));
        String conversationContext = buildConversationContext(sessionId);
        QueryRewriteService.RewrittenQuery rewritten =
                queryRewriteService.rewrite(sanitizedQuestion, conversationContext);

        // ---- ★ 并发发起两路 ----
        sink.next(statusEvent("retrieving", "并发检索：本地知识库 / 全网实时资讯..."));
        sink.next(statusEvent("web_searching", "正在全网检索最新资讯..."));
        DualPathRetrievalService.DualPath dual = dualPathEnabled
                ? dualPathRetrievalService.start(rewritten.primaryQuery(), rewritten.searchQueries(),
                        bookId, searchPageIds)
                : new DualPathRetrievalService.DualPath(
                        // 回滚开关：只走本地，联网那路给一个立即可用的失败结果
                        CompletableFuture.completedFuture(
                                DualPathRetrievalService.LocalOutcome.failed("disabled", 0)),
                        CompletableFuture.completedFuture(
                                DualPathRetrievalService.WebOutcome.failed("disabled", 0)));

        // ---- 等本地路（上限 localReadyTimeoutMs，超时视为不可用）----
        DualPathRetrievalService.LocalOutcome local = awaitLocal(dual.local());

        // 采纳条件：本地有料 + 余弦达标（-1 表示拿不到分数，按放行处理）。
        // 低于门槛的片段是"语义噪声"：既不能拿来做 Pass1 的依据，也不能作为引用展示给用户 ——
        // 否则会在答案末尾挂上一串与问题无关的「笔记来源」（实测 0.267 的"红烧肉"问题命中 4 条噪声）。
        // 引用列表与 Pass1 开关必须同源同判，否则用户看到的依据与模型实际用到的依据不一致。
        boolean localAdopted = local.hasChunks()
                && (local.topCosine() < 0 || local.topCosine() >= pass1CosineGate);
        List<SourceReference> localRefs = localAdopted
                ? local.result().sourceReferences()
                : List.of();
        List<String> localPageIds = localAdopted
                ? local.result().sourcePageIds()
                : List.of();
        boolean pass1Usable = localAdopted;

        if (localAdopted) {
            sink.next(statusEvent("retrieved",
                    "本地检索到 " + localRefs.size() + " 个相关片段"));
        } else if (!local.ok()) {
            sink.next(statusEvent("retrieved", "本地检索暂时不可用，等待全网检索..."));
        } else if (local.hasChunks()) {
            sink.next(statusEvent("retrieved", "本地笔记相关性不足，正在等待全网检索..."));
        } else {
            sink.next(statusEvent("retrieved", "本地未命中，正在等待全网检索..."));
        }

        // ---- Pass1：本地先答（答案首字 1~2s）----
        StringBuilder pass1Raw = new StringBuilder();
        StringBuilder pass1Visible = new StringBuilder();
        boolean pass1Opened = false;
        boolean aborted = false;

        if (pass1Usable) {
            pass1Opened = true;
            PassOutcome r1 = runStreamPass(sink, sessionId, sanitizedQuestion, roleInstruction,
                    lengthConstraint, local.result().contextString(), historySummary, regen, userId,
                    buildPass1Directive(), RetrievalMetrics.PASS_1);
            pass1Raw.append(r1.rawText());
            pass1Visible.append(r1.visibleText());
            aborted = r1.aborted() || sink.isCancelled();
            if (r1.error()) {
                // Pass1 就失败了：不再开 Pass2（否则用户会看到两个残缺的答案），直接按错误收尾
                if (!sink.isCancelled()) {
                    handleLlmError(sessionId, r1.errorMessage(), sink);
                }
                return;
            }
        }

        // ---- 中断：落库已输出内容，绝不开 Pass2 ----
        if (aborted) {
            log.info("Pass1 期间用户中断，不再启动 Pass2 | sessionId={}, partialLength={}",
                    sessionId, pass1Raw.length());
            finishAborted(sessionId, sanitizedQuestion, pass1Raw.toString(), localPageIds, localRefs,
                    userId, regen);
            sink.complete();
            return;
        }

        // ---- 挂起等联网（两段式的"停顿段"，实测 10~25s）----
        // 这一段既没有新 token 也没有新 phase —— 正文读起来像卡住了。
        // 推一条专用状态，前端据此在正文下方显示"还在推进"的细状态行（方案 §6.3 时间线：
        // 「15~20s 的等待段 = 心跳 + 已有一版答案可读」）。
        // 只在"真的要等"（联网尚未就绪）时才推，避免联网秒回时闪一下。
        if (pass1Opened && !dual.web().isDone()) {
            sink.next(statusEvent("waiting_web", "已基于笔记作答，正在补充全网实时资讯..."));
        }

        // ---- 等联网路（上限 webBudgetMs）----
        DualPathRetrievalService.WebOutcome web = awaitWeb(dual.web());
        boolean webUsable = web.usable()
                && web.sources() != null
                && !web.sources().isEmpty();

        if (dualPathEnabled && !webUsable) {
            // 降级：用户侧出声（不弹 Toast、不阻断对话），后端日志与指标已在 DualPath 里打过
            sink.next(noticeEvent(NOTICE_WEB_FALLBACK, "网络检索本次未取到结果，回答仅基于笔记与模型通用知识"));
            if (pass1Opened) {
                log.info("联网降级，Pass1 已产出内容，直接收尾 | sessionId={}, reason={}",
                        sessionId, web.reason());
                finishAnswer(sink, sessionId, sanitizedQuestion, pass1Raw.toString(),
                        localPageIds, localRefs, userId, regen);
                return;
            }
            // 本地也没料 → 纯模型兜底 + 强制声明
            log.info("两路均不可用，降级为纯模型作答 | sessionId={}, localOk={}, webReason={}",
                    sessionId, local.ok(), web.reason());
            sink.next(statusEvent("thinking", "正在生成回答..."));
            PassOutcome fallback = runStreamPass(sink, sessionId, sanitizedQuestion, roleInstruction,
                    lengthConstraint, NO_SOURCE_CONTEXT, historySummary, regen, userId,
                    buildSinglePassDirective(true), RetrievalMetrics.PASS_1);
            if (fallback.error() && !sink.isCancelled()) {
                handleLlmError(sessionId, fallback.errorMessage(), sink);
                return;
            }
            if (fallback.aborted() || sink.isCancelled()) {
                finishAborted(sessionId, sanitizedQuestion, fallback.rawText(), List.of(), List.of(),
                        userId, regen);
                sink.complete();
                return;
            }
            finishAnswer(sink, sessionId, sanitizedQuestion, fallback.rawText(),
                    List.of(), List.of(), userId, regen);
            return;
        }

        // ---- 引用编号：本地 1..n，全网接着 n+1..m ----
        int webStartIndex = localRefs.size() + 1;
        String webContext = webAugmentService.buildContext(web.sources(), webStartIndex);
        List<SourceReference> webRefs = webAugmentService.toReferences(web.sources(), webStartIndex);

        List<SourceReference> allRefs = new ArrayList<>(localRefs);
        allRefs.addAll(webRefs);
        List<String> allPageIds = new ArrayList<>(localPageIds);

        if (pass1Opened) {
            // ---- Pass2：自然衔接续写 ----
            sink.next(statusEvent("thinking", "正在整合全网实时资讯..."));
            PassOutcome r2 = runStreamPass(sink, sessionId, sanitizedQuestion, roleInstruction,
                    lengthConstraint, webContext, historySummary, regen, userId,
                    buildPass2Directive(pass1Visible.toString()), RetrievalMetrics.PASS_2);
            if (r2.error() && !sink.isCancelled()) {
                // Pass2 失败但 Pass1 已有内容：不把整轮判死，按已有内容收尾并提示
                log.warn("Pass2 生成失败，按 Pass1 已产出内容收尾 | sessionId={}", sessionId);
                sink.next(noticeEvent(NOTICE_WEB_FALLBACK, "全网补充段生成失败，以上为基于笔记的回答"));
                finishAnswer(sink, sessionId, sanitizedQuestion, pass1Raw.toString(),
                        localPageIds, localRefs, userId, regen);
                return;
            }
            if (r2.aborted() || sink.isCancelled()) {
                finishAborted(sessionId, sanitizedQuestion, pass1Raw + r2.rawText(),
                        allPageIds, allRefs, userId, regen);
                sink.complete();
                return;
            }
            finishAnswer(sink, sessionId, sanitizedQuestion, pass1Raw + r2.rawText(),
                    allPageIds, allRefs, userId, regen);
            return;
        }

        // ---- 本地无料：单段作答（全网结果一次给全）----
        sink.next(statusEvent("thinking", "正在生成回答..."));
        PassOutcome single = runStreamPass(sink, sessionId, sanitizedQuestion, roleInstruction,
                lengthConstraint, webContext, historySummary, regen, userId,
                buildSinglePassDirective(false), RetrievalMetrics.PASS_1);
        if (single.error() && !sink.isCancelled()) {
            handleLlmError(sessionId, single.errorMessage(), sink);
            return;
        }
        if (single.aborted() || sink.isCancelled()) {
            finishAborted(sessionId, sanitizedQuestion, single.rawText(), allPageIds, allRefs,
                    userId, regen);
            sink.complete();
            return;
        }
        finishAnswer(sink, sessionId, sanitizedQuestion, single.rawText(),
                allPageIds, allRefs, userId, regen);
    }

    // ==================== 两段式 · 阶段指令组装 ====================

    /**
     * Pass1 指令：本段是"基于笔记的开场"，必须为 Pass2 留出衔接空间。
     *
     * <p>两条约束都是风险清单里的直接对策：
     * <ul>
     *   <li><b>时效限定语</b>：第一段只用笔记作答，而联网数据 20s 后才到 —— 若这段说得斩钉截铁，
     *       Pass2 就可能要当面推翻自己。所以要求它对可能过时的内容用限定说法。</li>
     *   <li><b>软预算</b>：本地内容很丰富时 Pass1 会一路写下去，把 Pass2 的空间吃掉。
     *       这里明确"写到这里即自然收束"，让两段篇幅可控。</li>
     * </ul>
     */
    private String buildPass1Directive() {
        return """
            【本段定位】第一段（基于用户私有笔记的开场作答）。你之后还会拿到全网实时检索结果继续补充，
            所以本段【不是最终答案】，写完不需要收尾。
            【软预算】本段控制在 %d 字以内，接近上限时自然收束当前段落，不要写"综上所述"之类的总结。
            【时效限定】若笔记内容可能已不是当前最新情况，用"根据我的笔记…""笔记中记录的是…"这类
            限定说法交代依据，不要把笔记里的数据说成此刻的最新事实。
            【留白意识】若笔记对问题只能覆盖一部分，就只写覆盖到的部分，末句自然停住即可 ——
            稍后会有全网数据补齐其余部分，不要硬凑结论。
            """.formatted(pass1SoftBudgetChars);
    }

    /**
     * Pass2 指令：续写段。核心是把第一段已输出内容重放给模型 ——
     * 否则它不知道上文写到哪，必然重复。
     */
    private String buildPass2Directive(String pass1Visible) {
        return """
            【本段定位】第二段（续写段）。你刚刚已经输出了下面这段内容，现在拿到了全网实时检索结果。
            【衔接纪律 — 最重要】
            1. 必须"接着上文自然续写"，读起来是一整篇连续的回答；
            2. 严禁重复上文已经说过的内容、结论与例子；
            3. 不要重新开头、不要复述用户的问题、不要重新自我介绍、不要写"好的"这类过渡废话。
            【开场方式】本段第一个句子应当是自然过渡句（例如"补充全网最新资讯："），
            直接接在上文末尾念下去是通顺的。
            【内容重点】用【全网实时引用】补充上文没有覆盖的信息 —— 尤其是最新的、外部的、时效性强的部分。
            若全网数据与上文说法冲突，必须明确修正："你的笔记记录的是 X（更新于…），
            而当前最新的情况是 Z"。
            【软预算】本段控制在 500 字以内，写完自然收束，不要再做全文总结。

            【你在第一段已输出的内容（仅供衔接参考，严禁重复）】
            <previous_draft>
            %s
            </previous_draft>
            """.formatted(pass1Visible == null || pass1Visible.isBlank() ? "（无）" : pass1Visible);
    }

    /**
     * 单段作答指令（本地无料 / 两路全空）。
     *
     * @param noSource true = 连联网都不可用，必须声明"联网检索暂不可用"
     */
    private String buildSinglePassDirective(boolean noSource) {
        if (noSource) {
            return """
                【本段定位】单段作答（本地与联网都没有拿到可用内容）。
                【强制声明】回答的第一句必须说明："未在你的笔记中找到相关内容，联网检索暂时不可用，
                以下基于我的通用知识作答。"
                【约束】不得编造具体的数值、日期或人名；不得声称内容来自用户的知识库。
                """;
        }
        return """
            【本段定位】单段作答（本地笔记未命中，以下是全网实时检索结果）。
            【强制声明】回答的第一句必须说明："未在你的笔记中找到相关内容，以下回答基于全网搜索与我的通用知识。"
            【约束】优先采用【全网实时引用】中的事实；不得声称内容来自用户的知识库；
            不得编造具体的数值、日期或人名。
            """;
    }

    // ==================== 单次流式 Pass ====================

    /**
     * 跑一次流式生成，把可见 delta 推给前端，返回本段的产出与终态。
     *
     * <p>本方法<b>不做落库、不发 done</b> —— 那是外层编排的职责：两段引用要合并、
     * done 只能发一次、中断时要用合并后的引用落库。抽出它只为让两段共用一份易错逻辑
     * （中断判定、思维链过滤、TTFT 打点）。
     */
    private PassOutcome runStreamPass(FluxSink<ChatStreamEvent> sink,
                                      String sessionId, String question,
                                      String roleInstruction, String lengthConstraint, String context,
                                      String historySummary, RegenerateContext regen, Long userId,
                                      String passDirective, String passTag) {
        String userGlobalMemory = buildUserMemoryBlock(userId);
        TokenStream tokenStream = mainChatAssistant.chat(
                sessionId, roleInstruction, lengthConstraint, context,
                historySummary, regen.instruction(), userGlobalMemory, passDirective, question
        );

        StringBuilder raw = new StringBuilder();
        StringBuilder visibleBuf = new StringBuilder();
        ThinkingTagFilter tagFilter = new ThinkingTagFilter();
        AtomicBoolean aborted = new AtomicBoolean(false);
        AtomicLong firstTokenAt = new AtomicLong(0);
        long startedAt = System.currentTimeMillis();
        CompletableFuture<PassOutcome> finished = new CompletableFuture<>();

        retrievalMetrics.recordPassTriggered(passTag);

        tokenStream
                .onPartialResponse(token -> {
                    if (sink.isCancelled()) {
                        if (aborted.compareAndSet(false, true)) {
                            log.info("用户主动中断，硬中断 LLM 流 | sessionId={}, pass={}, partialLength={}",
                                    sessionId, passTag, raw.length());
                            finished.complete(new PassOutcome(raw.toString(), visibleBuf.toString(),
                                    true, false, null));
                            throw new RuntimeException("CLIENT_ABORTED");
                        }
                        return;
                    }
                    if (firstTokenAt.compareAndSet(0, System.currentTimeMillis())) {
                        long ttft = firstTokenAt.get() - startedAt;
                        retrievalMetrics.recordPassTtft(passTag, ttft);
                        log.info("{} 首字到达 | sessionId={}, ttft={}ms", passTag, sessionId, ttft);
                    }
                    raw.append(token);
                    String visible = tagFilter.filter(token);
                    if (!visible.isEmpty()) {
                        visibleBuf.append(visible);
                        sink.next(messageEvent(visible));
                    }
                })
                .onCompleteResponse(response -> {
                    if (aborted.get()) {
                        return;
                    }
                    finished.complete(new PassOutcome(raw.toString(), visibleBuf.toString(),
                            false, false, null));
                })
                .onError(error -> {
                    if (aborted.get() || "CLIENT_ABORTED".equals(error.getMessage())) {
                        finished.complete(new PassOutcome(raw.toString(), visibleBuf.toString(),
                                aborted.get(), false, null));
                        return;
                    }
                    log.error("{} 流式响应异常 | sessionId={}", passTag, sessionId, error);
                    finished.complete(new PassOutcome(raw.toString(), visibleBuf.toString(),
                            false, true, error));
                })
                .start();

        try {
            return finished.get(passTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.warn("{} 等待超时 {}ms，按已输出内容收尾 | sessionId={}",
                    passTag, passTimeoutMs, sessionId);
            return new PassOutcome(raw.toString(), visibleBuf.toString(), true, false, null);
        } catch (Exception e) {
            log.error("{} 等待异常，按已输出内容收尾 | sessionId={}", passTag, sessionId, e);
            return new PassOutcome(raw.toString(), visibleBuf.toString(), true, false, null);
        }
    }

    /**
     * 一段流式生成的终态。
     *
     * @param rawText    含 {@code <thinking>} 的原始输出（落库用，与改造前一致）
     * @param visibleText 已过滤思维链的可见输出（Pass2 衔接用 —— 回放思维链只会干扰续写）
     * @param aborted    是否被用户中断 / 等待超时
     * @param error      是否发生生成错误
     * @param errorMessage 错误对象（供统一错误分类）
     */
    private record PassOutcome(String rawText, String visibleText,
                               boolean aborted, boolean error, Throwable errorMessage) {
    }

    // ==================== 收尾 ====================

    /** 正常收尾：done（合并引用）→ 落库 → 摘要 → 推荐（推荐线程负责关流） */
    private void finishAnswer(FluxSink<ChatStreamEvent> sink, String sessionId, String question,
                              String answer, List<String> sourcePageIds,
                              List<SourceReference> sourceReferences, Long userId,
                              RegenerateContext regen) {
        sink.next(doneEvent(sourcePageIds, sourceReferences));
        CompletableFuture<String> assistantRowId =
                persistMessages(sessionId, question, answer, sourcePageIds, sourceReferences,
                        userId, regen.skipUserMessage());
        chatSummaryService.triggerAsyncSummarization(sessionId);
        triggerSuggestionAsync(sink, sessionId, question, answer, assistantRowId);
    }

    /** 中断收尾：不发 done（本轮本就不完整），落库已生成部分并关流 */
    private void finishAborted(String sessionId, String question, String partial,
                               List<String> sourcePageIds, List<SourceReference> sourceReferences,
                               Long userId, RegenerateContext regen) {
        if (partial == null || partial.isEmpty()) {
            return;
        }
        persistMessages(sessionId, question,
                partial + "\n\n*[用户已中断回答]*", sourcePageIds, sourceReferences,
                userId, regen.skipUserMessage());
    }

    /** 两路全空时的兜底上下文：允许用通用知识答，但必须声明且禁止假装引用笔记 */
    private static final String NO_SOURCE_CONTEXT = """
        【重要指令】用户的问题未能在其私有知识库中找到相关内容，且此刻联网检索不可用。请遵守：
        1. 回答的第一句必须明确说明：「未在你的笔记中找到相关内容，联网检索暂时不可用，以下基于我的通用知识作答。」
        2. 可以运用你的通用知识正常作答，但不得编造具体的数值、日期或人名。
        3. 严禁声称这些内容来自用户的知识库 —— 本轮没有引用用户的任何笔记。
        """;

    private DualPathRetrievalService.LocalOutcome awaitLocal(CompletableFuture<DualPathRetrievalService.LocalOutcome> future) {
        try {
            return future.get(localReadyTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.warn("本地路 {}ms 内未返回，本轮不开 Pass1（联网路继续跑）", localReadyTimeoutMs);
            return DualPathRetrievalService.LocalOutcome.failed("local_timeout", localReadyTimeoutMs);
        } catch (Exception e) {
            log.warn("本地路等待异常，本轮不开 Pass1", e);
            return DualPathRetrievalService.LocalOutcome.failed("local_error", 0);
        }
    }

    private DualPathRetrievalService.WebOutcome awaitWeb(CompletableFuture<DualPathRetrievalService.WebOutcome> future) {
        try {
            return future.get(webBudgetMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.warn("联网路 {}ms 内未返回，放弃等待并降级", webBudgetMs);
            return DualPathRetrievalService.WebOutcome.failed("web_timeout", webBudgetMs);
        } catch (Exception e) {
            log.warn("联网路等待异常，放弃等待并降级", e);
            return DualPathRetrievalService.WebOutcome.failed("web_error", 0);
        }
    }

    // ==================== 智能追问（Piggybacking on SSE） ====================

    private void triggerSuggestionAsync(FluxSink<ChatStreamEvent> sink,
                                        String sessionId, String question, String fullAnswer,
                                        CompletableFuture<String> assistantRowId) {
        Thread.startVirtualThread(() -> {
            try {
                if (sink.isCancelled()) {
                    return;
                }
                List<String> suggestions = suggestionService.generate(question, fullAnswer);
                if (!suggestions.isEmpty() && !sink.isCancelled()) {
                    sink.next(suggestionsEvent(suggestions));
                }
                // 生成结果回写 assistant 行：SSE 事件是一次性的，
                // 刷新页面 / 换标签页后历史接口必须能取到这组推荐（否则「猜你想问」块消失）
                if (!suggestions.isEmpty()) {
                    try {
                        String rowId = assistantRowId.get(60, TimeUnit.SECONDS);
                        if (rowId != null) {
                            chatSessionService.attachSuggestions(rowId, suggestions);
                        }
                    } catch (Exception e) {
                        log.warn("推荐问题回写失败（SSE 推送不受影响） | sessionId={}", sessionId, e);
                    }
                }
            } catch (Exception e) {
                log.warn("追问建议异步生成失败 | sessionId={}", sessionId, e);
            } finally {
                if (!sink.isCancelled()) {
                    sink.complete();
                }
            }
        });
    }

    // ==================== 重新生成上下文消费（一次性 Token + 负例锚定） ====================

    /**
     * 一次性 token 的消费产物。
     *
     * @param instruction     注入本轮 System Prompt 的临时片段；普通提问为空串（零污染）
     * @param skipUserMessage 本次是否为 Regenerate 续接。true 时落库【跳过 user 行 Insert】。
     */
    private record RegenerateContext(String instruction, boolean skipUserMessage) {
        /** 普通提问：既无注入片段，也不需要跳过任何落库动作 */
        private static final RegenerateContext NONE = new RegenerateContext("", false);
    }

    /**
     * getAndDelete 一次性消费：仅携带有效 token 的 regenerate 重发请求命中；
     * token 缺失/过期/已被消费均降级为普通对话（instruction 为空串 + 不跳过 user 落库）。
     * 双模式组装强语气 XML 注入块：
     *   A 有指令 → <correction_instruction> 重写要求 + <rejected_version> 供参考勿重踏错误；
     *   B 无指令 → <regeneration_rule> 强制求变 + <rejected_version> 必须避开的结构。
     *
     * ⚠️ skipUserMessage 的判定依据是「token 是否被成功消费」，而【不是】「注入片段是否为空」：
     *    ChatSessionServiceImpl.regenerate() 是【无条件】签发 token 的（指令为空也必带负例），
     *    所以 token 一旦被消费，就证明上游已经走过截断流程 —— 那条原始 user 行必然还在库里。
     */
    private RegenerateContext resolveRegenerateContext(String token) {
        RegenerateInstructionStore.RegeneratePayload payload = regenerateInstructionStore.consume(token);
        if (payload == null) {
            return RegenerateContext.NONE;
        }
        String instruction = chatInputGuard.sanitize(payload.instruction());
        String rejectedDraft = neutralizeDraft(payload.rejectedDraft());
        // 退化输入（有 token 但指令与负例都为空）：注入片段可以为空，但 skipUserMessage 仍必须是 true
        if (instruction.isBlank() && rejectedDraft.isBlank()) {
            return new RegenerateContext("", true);
        }

        log.info("重新生成上下文已消费并注入临时 System Prompt | token={}, hasInstruction={}, draftLength={}",
                token, !instruction.isBlank(), rejectedDraft.length());

        StringBuilder sb = new StringBuilder();
        if (!instruction.isBlank()) {
            sb.append("<correction_instruction>\n")
                    .append("用户对你的上一版回答不满意，并提出了明确的修改意见：\n")
                    .append(instruction).append("\n")
                    .append("请你完全遵照上述意见进行重写。\n")
                    .append("</correction_instruction>\n");
            if (!rejectedDraft.isBlank()) {
                sb.append("<rejected_version>\n")
                        .append("以下是你上一版被否决的回答供参考，请勿重复其中的错误：\n")
                        .append(rejectedDraft).append("\n")
                        .append("</rejected_version>");
            }
        } else if (!rejectedDraft.isBlank()) {
            sb.append("<regeneration_rule>\n")
                    .append("用户对你的上一版回答不满意，要求重新生成。")
                    .append("你必须改变行文结构、切入角度或代码实现思路，严禁进行简单的同义词替换或复述！\n")
                    .append("</regeneration_rule>\n")
                    .append("<rejected_version>\n")
                    .append("以下是你上一版被否决的回答，你必须避开这种结构和表述：\n")
                    .append(rejectedDraft).append("\n")
                    .append("</rejected_version>");
        }
        return new RegenerateContext(sb.toString(), true);
    }

    /**
     * 负例草稿消毒：
     *   1. 剔除 <thinking> 段 —— 落库的 fullResponse 含思维链，直接回注会干扰本轮推理规范；
     *   2. 剥离与本机制同名 XML 标签 —— 防旧答案/历史注入内容提前闭合 <rejected_version> 造成提示词塌陷。
     */
    private String neutralizeDraft(String draft) {
        if (draft == null || draft.isBlank()) {
            return "";
        }
        return draft.replaceAll("(?s)<thinking>.*?</thinking>", "")
                .replaceAll("(?s)</?(correction_instruction|regeneration_rule|rejected_version)>", "")
                .trim();
    }

    // ==================== 全局语义记忆注入（Phase 3） ====================

    /**
     * 正式记忆库组装 XML 隔离块强注入 System Prompt：正向偏好与负向禁忌分桶隔离，
     * 以最大强度触发模型对约束的敬畏。fact 来自 LLM 提取再经 DB 回注，属不可信输入，
     * 注入前二次消毒（记忆投毒三道闸）；查询/组装异常降级空串，普通对话零污染。
     */
    private String buildUserMemoryBlock(Long userId) {
        try {
            Map<String, List<String>> memory = semanticMemoryService.getUserGlobalMemory(userId);
            if (memory.isEmpty()) {
                return "";
            }
            List<String> preferences = sanitizeFacts(memory.get(SemanticRuleType.PREFERENCE.getCode()));
            List<String> constraints = sanitizeFacts(memory.get(SemanticRuleType.CONSTRAINT.getCode()));
            if (preferences.isEmpty() && constraints.isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            sb.append("【用户全局记忆 — 必须遵守】以下为跨会话沉淀的用户长期习惯与禁忌，")
                    .append("优先级高于风格指令、低于核心纪律：\n");
            if (!preferences.isEmpty()) {
                sb.append("<user_preferences>\n");
                preferences.forEach(fact -> sb.append("  - ").append(fact).append('\n'));
                sb.append("</user_preferences>\n");
            }
            if (!constraints.isEmpty()) {
                sb.append("<forbidden_rules>\n");
                constraints.forEach(fact -> sb.append("  - ").append(fact).append('\n'));
                sb.append("</forbidden_rules>");
            }
            return sb.toString().trim();
        } catch (Exception e) {
            log.warn("全局记忆组装失败，降级为无记忆普通对话 | userId={}", userId, e);
            return "";
        }
    }

    private List<String> sanitizeFacts(List<String> facts) {
        if (facts == null || facts.isEmpty()) {
            return List.of();
        }
        return facts.stream()
                .map(promptSanitizer::sanitizeFact)
                .filter(Objects::nonNull)
                .toList();
    }

    // ==================== 多轮对话上下文提取（供 QueryRewrite 指代消解） ====================

    private String buildConversationContext(String sessionId) {
        return buildConversationContext(sessionId, 6);
    }

    /**
     * 取最近 keep 条消息拼成对话原文（查询改写 / 意图路由 / 非 RAG 上下文共用）。
     * 注意：调用时机在当前提问进入 ChatMemory 之前，这里天然只含【历史】轮次。
     */
    private String buildConversationContext(String sessionId, int keepMessages) {
        try {
            List<ChatMessage> messages = chatMemoryProvider.get(sessionId).messages();
            if (messages == null || messages.isEmpty()) {
                return "";
            }

            int size = messages.size();
            int keep = Math.min(keepMessages, size);
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

    private void handleLlmError(String sessionId, Throwable error, FluxSink<ChatStreamEvent> sink) {
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

    /**
     * 异步落库 user / assistant 两行，并返回 assistant 行主键的兑现句柄。
     *
     * @param skipUserMessage Regenerate 续接时传 true，【跳过 user 行 Insert】。
     *
     * <p>为什么 Regenerate 必须跳过 user 落库（2026-10-11 修复的脏数据缺陷）：
     * <pre>
     * ChatSessionServiceImpl.regenerate() 的截断条件是 created_at >= 目标 assistant 行的 created_at
     * 而 chat_message.created_at 是 DATETIME(3)（毫秒精度），同一轮里 user 行比 assistant 行早约 30ms
     * —— 所以那条原始 user 行【不在删除范围内】，它活了下来。若此处再无脑插一条 user 行，
     * 库里就会出现两条一模一样的提问，刷新页面走 GET /messages 时会渲染出重复气泡，
     * 并且会顺着 ChatMemory 二次污染后续上下文 Token。
     * </pre>
     */
    private CompletableFuture<String> persistMessages(String sessionId, String question, String answer,
                                                      List<String> sourcePageIds,
                                                      List<SourceReference> sourceReferences,
                                                      Long userId, boolean skipUserMessage) {
        CompletableFuture<String> assistantRowId = new CompletableFuture<>();
        // 落库运行在 aiTaskExecutor 线程池，同样丢失请求线程 ThreadLocal；
        // 显式 bind userId 供 tryUpdateSessionTitle 的会话归属校验使用，finally remove 防线程池复用泄漏
        aiTaskExecutor.execute(() -> {
            BaseContext.setCurrentId(userId);
            String rowId = null;
            // ★ B1 高危快通道需要「本轮 user 行的主键」（确认锚点 = "cm_" + 它）——
            //   改造前这里的返回值被丢掉了，导致快通道无法拿到 messageId。
            String userRowId = null;
            if (skipUserMessage) {
                log.info("Regenerate 续接，跳过 user 消息落库（原始提问行未被截断） | sessionId={}", sessionId);
            } else {
                try {
                    userRowId = chatSessionService.saveMessage(sessionId, "user", question, List.of(), List.of(), null);
                } catch (Exception e) {
                    log.error("用户消息持久化失败 | sessionId={}", sessionId, e);
                }
            }
            try {
                rowId = chatSessionService.saveMessage(sessionId, "assistant", answer, sourcePageIds,
                        sourceReferences, null);
            } catch (Exception e) {
                log.error("AI 回复持久化失败 | sessionId={}", sessionId, e);
            } finally {
                // 无论成败都要兑现，推荐线程的 get(…, timeout) 才不会空等
                assistantRowId.complete(rowId);
            }
            try {
                tryUpdateSessionTitle(sessionId, question);
            } finally {
                BaseContext.removeCurrentId();
            }
            // Phase 3：落库链路完成后为会话武装延时语义扫描（冷却锁自然去重，内部全捕获不影响主链路）
            semanticScanProducer.armSessionScan(sessionId, userId);

            // Phase 4 · B1：高危快通道（纯规则、零 LLM，内部全捕获，绝不影响主链路）。
            //   ① 命中词表 → 本轮 user 原文【立即投递】到 graphiti.write.queue，绕过 5 分钟 delay 队列；
            //   ② ★ 它<b>不是</b>常规链路的替代品：命中后常规链路照旧武装，因此这条消息
            //      「必然」会被投递两次 —— 防重完全由 GraphitiWriteConsumer 的 DB CAS 占位承担（铁律 1）。
            //   ③ ★ 只对「本轮新落库的 user 行」投：Regenerate 续接时 userRowId 为 null，
            //      那条原始提问早已投递过，再投一次只会白烧一次 CAS 并留下一条 CAS 失败日志。
            //   ④ 与 armSessionScan 的相对顺序无关：两条路径互不依赖，各自幂等。
            if (userRowId != null) {
                graphitiScanProducer.dispatchHighRiskNow(sessionId, userRowId, question, userId);
            }
        });
        return assistantRowId;
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

    /**
     * notice 事件（2026-10-12 新增）：承载降级提示，与 status 分离。
     *
     * <p>为什么不复用 status：phase 是"步骤条上的一个阶段"，而 notice 是"这一轮的可靠性说明"
     * —— 它会一直显示到回答结束，不该被后续 phase 顶掉。语义不同，事件就该分开。
     */
    private ChatStreamEvent noticeEvent(String code, String message) {
        try {
            return new ChatStreamEvent("notice", objectMapper.writeValueAsString(
                    Map.of("code", code, "message", message)
            ));
        } catch (JsonProcessingException e) {
            return new ChatStreamEvent("notice", "{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
        }
    }

    /**
     * done 事件的引用序列化（统一溯源）。
     *
     * <p>{@code sourceType} 与 {@code url} 让本地命中与网外命中共用同一结构，
     * 前端按 sourceType 决定点击行为（本地 → 展开分块原文；外网 → 新标签页打开链接）。
     * 双路并发下这里的列表会同时含两类来源，{@code index} 全局连续（本地 1..n，网页 n+1..m）。
     */
    private ChatStreamEvent doneEvent(List<String> sourcePageIds, List<SourceReference> sourceReferences) {
        try {
            List<Map<String, Object>> refs = sourceReferences.stream()
                    .map(r -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("index", r.index());
                        m.put("title", r.title() != null ? r.title() : "");
                        m.put("pageId", r.pageId() != null ? r.pageId() : "");
                        m.put("chunkIndex", r.chunkIndex());
                        m.put("textSnippet", r.textSnippet());
                        m.put("sourceType", r.sourceType() != null ? r.sourceType().name() : "LOCAL_RAG");
                        m.put("url", r.url());
                        return m;
                    })
                    .toList();
            return new ChatStreamEvent("done", objectMapper.writeValueAsString(
                    Map.of("sourcePageIds", sourcePageIds, "sourceReferences", refs)
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

    private ChatStreamEvent suggestionsEvent(List<String> suggestions) {
        try {
            return new ChatStreamEvent("suggestions", objectMapper.writeValueAsString(suggestions));
        } catch (JsonProcessingException e) {
            return new ChatStreamEvent("suggestions", "[]");
        }
    }

    public record ChatStreamEvent(String type, String data) {
    }
}
