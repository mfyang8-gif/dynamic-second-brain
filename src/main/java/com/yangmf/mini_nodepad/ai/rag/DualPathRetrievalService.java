package com.yangmf.mini_nodepad.ai.rag;

import com.yangmf.mini_nodepad.ai.component.RetrievalMetrics;
import com.yangmf.mini_nodepad.ai.component.WebAugmentService;
import com.yangmf.mini_nodepad.ai.rag.PageRagRetrievalService.ProcessedResult;
import com.yangmf.mini_nodepad.ai.rag.PageRagRetrievalService.RagChunk;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 双路并发检索编排（用户 2026-10-12 裁决：<b>结构根治 · 恒并行双路</b>）。
 *
 * <p>为什么要推翻上一版的「串行漏斗 + 判卷器」：只要存在"本地命中就阻断联网"的判定，
 * 就必然有有效性盲区 ——
 * <ul>
 *   <li><b>时效劫持</b>：一年前的笔记写着"128k"，判卷器读后判"包含答案" → 阻断联网 → 完美但过时的错答；</li>
 *   <li><b>局部满足</b>：对比型问题里"我方功能"命中，"竞品"只能靠编；</li>
 *   <li><b>实体混淆</b>：本地有个测试项目叫"张三"，公众人物"张三"就永远查不到。</li>
 * </ul>
 * 这些盲区的共同根因是"用一次上游判定替用户做取舍"。现在改为：<b>两路同时发起、都拿到，
 * 由主模型在提示词约束下交叉裁决</b> —— 盲区结构上不存在了。
 *
 * <p>代价是每轮非闲聊都会真的打一次 MCP（用户已明确接受），以及主模型输入 token 上升。
 * 换来的收益是「时效劫持」从"被拦在网络之外"变成"进入模型后必须被识别"。
 *
 * <p>本类只负责<b>发起与归一化</b>（把异常、超时、空结果统一成可判定的 outcome + 埋点），
 * 编排策略（谁先回来、要不要开 Pass1、Pass2 怎么续写）留在 ChatStreamService。
 * 这样职责边界清楚：本类换了实现，编排逻辑不用动。
 */
@Slf4j
@Service
public class DualPathRetrievalService {

    private final PageRagRetrievalService pageRagRetrievalService;
    private final QdrantTemplate qdrantTemplate;
    private final WebAugmentService webAugmentService;
    private final RetrievalMetrics metrics;

    /** 主检索集合名（稠密余弦快筛同样要指名集合） */
    @Value("${qdrant.collection-name:dynamic_brain}")
    private String ragCollectionName;

    /** 每路独立虚拟线程：I/O 密集且互不阻塞，虚拟线程是零成本的选择 */
    private final ExecutorService pathExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public DualPathRetrievalService(PageRagRetrievalService pageRagRetrievalService,
                                    QdrantTemplate qdrantTemplate,
                                    WebAugmentService webAugmentService,
                                    RetrievalMetrics metrics) {
        this.pageRagRetrievalService = pageRagRetrievalService;
        this.qdrantTemplate = qdrantTemplate;
        this.webAugmentService = webAugmentService;
        this.metrics = metrics;
    }

    /**
     * 并发发起两路检索。<b>本方法立即返回</b> —— 两路都在各自的虚拟线程里跑，
     * 调用方用 {@code future.get(...)} 按自己的时间预算分别等待，
     * 这就是"本地 1s 就能开答、联网 20s 慢慢来"得以成立的原因。
     *
     * @param localQuery 本地检索用的查询（QueryRewrite 的 primaryQuery）
     * @param webQueries 联网搜索词（QueryRewrite 的 searchQueries，1~N 条）
     * @param bookId     知识库范围
     * @param pageIds    笔记范围（空 = 全库）
     */
    public DualPath start(String localQuery, List<String> webQueries, String bookId, List<String> pageIds) {
        CompletableFuture<LocalOutcome> local = CompletableFuture.supplyAsync(
                () -> runLocal(localQuery, bookId, pageIds), pathExecutor);
        CompletableFuture<WebOutcome> web = CompletableFuture.supplyAsync(
                () -> runWeb(webQueries), pathExecutor);
        return new DualPath(local, web);
    }

    // ==================== 本地路 ====================

    private LocalOutcome runLocal(String query, String bookId, List<String> pageIds) {
        long start = System.currentTimeMillis();
        try {
            List<RagChunk> chunks = pageRagRetrievalService.retrieveChunks(query, bookId, pageIds);
            ProcessedResult result = pageRagRetrievalService.process(chunks);
            // 开 Pass1 只看真实余弦分（RRF 分数与余弦不可比，见 QdrantTemplate 的说明）
            float topCosine = topCosine(query, bookId, pageIds);
            long cost = System.currentTimeMillis() - start;
            metrics.recordPath(RetrievalMetrics.PATH_LOCAL, cost, true);
            log.info("本地路检索完成 | chunks={}, topCosine={}, cost={}ms",
                    chunks.size(), topCosine, cost);
            return new LocalOutcome(true, result, topCosine, cost, "ok");
        } catch (Exception e) {
            long cost = System.currentTimeMillis() - start;
            metrics.recordPath(RetrievalMetrics.PATH_LOCAL, cost, false);
            metrics.recordDegrade(RetrievalMetrics.PATH_LOCAL, "qdrant_error");
            log.warn("本地路检索失败，本次视为本地无料（联网路不受影响） | bookId={}, cost={}ms", bookId, cost, e);
            return new LocalOutcome(false, null, -1f, cost, "qdrant_error");
        }
    }

    /**
     * 取纯稠密余弦 top 分。
     *
     * <p>⚠️ 绝不能复用主检索链路的分数：那条走 RRF 融合，返回的是排名制分数（典型 0.01~0.03），
     * 拿它跟 0.28 的阈值比会 100% 误判成"本地没料"，Pass1 就永远开不起来。
     *
     * @return 余弦分；基础设施异常返回 -1（视为"拿不到分数"，放行开 Pass1）
     */
    private float topCosine(String question, String bookId, List<String> pageIds) {
        try {
            Map<String, Object> filters = new HashMap<>();
            filters.put("bookId", bookId);
            if (pageIds != null && !pageIds.isEmpty()) {
                filters.put("pageId", pageIds);
            }
            List<QdrantTemplate.VectorSearchResult> dense =
                    qdrantTemplate.searchByDenseSimilarity(ragCollectionName, question, filters, 1);
            if (dense == null || dense.isEmpty()) {
                return 0f;
            }
            return dense.get(0).getScore();
        } catch (Exception e) {
            log.warn("余弦快筛失败，跳过 Pass1 门槛判定（按放行处理） | bookId={}", bookId, e);
            return -1f;
        }
    }

    // ==================== 联网路 ====================

    private WebOutcome runWeb(List<String> queries) {
        long start = System.currentTimeMillis();
        try {
            WebAugmentService.WebAugmentResult result = webAugmentService.augment(queries);
            long cost = System.currentTimeMillis() - start;
            if (result.usable()) {
                metrics.recordPath(RetrievalMetrics.PATH_WEB, cost, true);
            } else {
                metrics.recordPath(RetrievalMetrics.PATH_WEB, cost, false);
                metrics.recordDegrade(RetrievalMetrics.PATH_WEB, result.reason());
            }
            return new WebOutcome(result.usable(), result.sources(), cost, result.reason());
        } catch (Exception e) {
            long cost = System.currentTimeMillis() - start;
            metrics.recordPath(RetrievalMetrics.PATH_WEB, cost, false);
            metrics.recordDegrade(RetrievalMetrics.PATH_WEB, "mcp_timeout");
            log.warn("联网路检索失败，本次降级为无全网来源 | cost={}ms", cost, e);
            return new WebOutcome(false, List.of(), cost, "mcp_timeout");
        }
    }

    // ==================== 结果模型 ====================

    public record DualPath(CompletableFuture<LocalOutcome> local, CompletableFuture<WebOutcome> web) {
    }

    /**
     * @param ok         本地路是否基础设施正常（false = 检索失败，不等于"没查到"）
     * @param result     本地检索结果（含 contextString 与 rawChunks）
     * @param topCosine  真实余弦 top 分；-1 表示拿不到分数
     * @param costMs     耗时
     * @param failReason ok=false 时的原因，便于埋点归因
     */
    public record LocalOutcome(boolean ok, ProcessedResult result, float topCosine,
                               long costMs, String failReason) {

        /** 本地是否"有料可答"：路通 + 命中 + 还有 chunk */
        public boolean hasChunks() {
            return ok && result != null && result.hasResults()
                    && result.rawChunks() != null && !result.rawChunks().isEmpty();
        }

        public static LocalOutcome failed(String reason, long costMs) {
            return new LocalOutcome(false, null, -1f, costMs, reason);
        }
    }

    /**
     * @param usable     是否取到可用网页内容
     * @param sources    结构化网页来源（已过防护与去重，index 由编排层统一分配）
     * @param costMs     耗时
     * @param reason     usable=false 时的原因（no_query / empty_result / mcp_timeout / all_dropped）
     */
    public record WebOutcome(boolean usable, List<WebAugmentService.WebSource> sources,
                             long costMs, String reason) {

        public static WebOutcome failed(String reason, long costMs) {
            return new WebOutcome(false, List.of(), costMs, reason);
        }
    }
}
