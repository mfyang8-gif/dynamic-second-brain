package com.yangmf.mini_nodepad.ai.component;

import com.yangmf.mini_nodepad.ai.rag.SourceReference;
import com.yangmf.mini_nodepad.pojo.vo.WebSearchResultVO;
import com.yangmf.mini_nodepad.service.WebSearchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 联网预检索管线（用户 2026-10-12 裁决：严格执行「预检索注入」，绝不给主模型挂 Tool）。
 *
 * <p>流水线：
 * <pre>
 *   ① 搜索词 —— 由调用方传入（复用 QueryRewrite 的产物，零额外小模型调用）
 *   ② 并发召回 —— 多条搜索词各起一条虚拟线程打 WebSearchService（百炼 MCP），单条失败局部降级
 *   ③ URL 去重 + 语义去重 —— 跨站转载只留一条（{@link SemanticDeduplicator}）
 *   ④ 注入防护 —— 逐条消毒 + 检出丢弃（{@link WebContentGuard}）
 *   ⑤ 结构化输出 —— 返回 {@link WebSource} 列表，index 与上下文由编排层统一组装
 * </pre>
 *
 * <p>为什么"预检索注入"而不是挂 Tool：挂 Tool 会让主模型进入 Function Calling 思考循环
 * （多一次往返 + 生成不确定性 + 流式首字被拖慢）；预检索把联网降级成一次普通生成，
 * 流式体验与本地知识库回答完全一致。
 *
 * <p><b>2026-10-12 重构要点（相对上一版）</b>：
 * <ul>
 *   <li>取消"伪装成知识库上下文"的旧口径 —— 那会让模型把自己的私有笔记与全网内容混为一谈，
 *       且完全绕过了来源交代。现在网页内容以 {@code <untrusted_web_content>} 显式隔离；</li>
 *   <li>接线注入防护与语义去重；</li>
 *   <li>只产出<b>结构化来源</b>，不再自己拼上下文 —— 因为引用编号必须与本地来源全局连续
 *       （本地 1..n，网页 n+1..m），而编号只有拿到两路结果的编排层才知道。</li>
 * </ul>
 */
@Slf4j
@Service
public class WebAugmentService {

    private final WebSearchService webSearchService;
    private final WebContentGuard webContentGuard;
    private final SemanticDeduplicator semanticDeduplicator;
    private final RetrievalMetrics metrics;

    /** 单条搜索词取回几条网页（合并后再统一截断） */
    @Value("${web-search.pre-search-limit:5}")
    private int perQueryLimit;

    /** 最多并发跑几条搜索词 */
    @Value("${web-search.pre-search-max-queries:2}")
    private int maxQueries;

    /** 注入上下文里最多保留几条来源（与本地 RAG 的 max-results 保持同一量级） */
    @Value("${web-search.pre-search-context-top-n:5}")
    private int contextTopN;

    @Value("${web-search.pre-search-timeout-ms:8000}")
    private long searchTimeoutMs;

    /** 注入给模型的单条摘要截断长度 */
    private static final int SNIPPET_INJECT_LEN = 600;

    /** 落库/展示用的引用片段截断长度 */
    private static final int SNIPPET_REF_LEN = 120;

    private final ExecutorService searchExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public WebAugmentService(WebSearchService webSearchService,
                             WebContentGuard webContentGuard,
                             SemanticDeduplicator semanticDeduplicator,
                             RetrievalMetrics metrics) {
        this.webSearchService = webSearchService;
        this.webContentGuard = webContentGuard;
        this.semanticDeduplicator = semanticDeduplicator;
        this.metrics = metrics;
    }

    /**
     * 抓取、清洗并返回结构化网页来源。
     *
     * @param queries 搜索词（1~N 条，超出 maxQueries 的截断；空则返回不可用）
     * @return 结果；{@code usable=false} 表示本次联网未取到任何可用内容
     *         （调用方应降级为「基于模型自身知识作答」并如实告知）
     */
    public WebAugmentResult augment(List<String> queries) {
        List<String> effective = normalizeQueries(queries);
        if (effective.isEmpty()) {
            return WebAugmentResult.unusable("no_query");
        }

        long start = System.currentTimeMillis();
        List<CompletableFuture<List<WebSearchResultVO>>> futures = effective.stream()
                .map(q -> CompletableFuture.supplyAsync(() -> safeQuery(q), searchExecutor))
                .toList();

        List<WebSearchResultVO> merged = new ArrayList<>();
        int failedQueries = 0;
        for (CompletableFuture<List<WebSearchResultVO>> future : futures) {
            try {
                merged.addAll(future.get(searchTimeoutMs, TimeUnit.MILLISECONDS));
            } catch (Exception e) {
                failedQueries++;
                log.warn("联网预检索单条搜索超时/失败，忽略该路结果 | cost={}ms",
                        System.currentTimeMillis() - start, e);
            }
        }

        if (merged.isEmpty()) {
            log.info("联网预检索无召回 | queries={}, failed={}, cost={}ms",
                    effective, failedQueries, System.currentTimeMillis() - start);
            return WebAugmentResult.unusable(failedQueries > 0 ? "mcp_timeout" : "empty_result");
        }

        // ① URL 级去重（保序：Rerank 分高的在前）
        Map<String, WebSearchResultVO> byUrl = new LinkedHashMap<>();
        for (WebSearchResultVO item : merged) {
            if (item == null || item.getUrl() == null || item.getUrl().isBlank()) {
                continue;
            }
            byUrl.putIfAbsent(item.getUrl(), item);
        }
        List<WebSearchResultVO> urlDeduped = new ArrayList<>(byUrl.values());

        // ② 注入防护：逐条消毒 / 检出丢弃（先于语义去重，避免把被丢弃项纳入判重基准）
        List<WebSource> guarded = new ArrayList<>();
        int dropped = 0;
        for (WebSearchResultVO item : urlDeduped) {
            String raw = item.getSnippet() != null && !item.getSnippet().isBlank()
                    ? item.getSnippet()
                    : item.getTitle();
            WebContentGuard.Guarded result = webContentGuard.process(raw);
            if (result.dropped()) {
                dropped++;
                continue;
            }
            guarded.add(new WebSource(
                    item.getTitle(),
                    item.getUrl(),
                    truncate(result.text(), SNIPPET_INJECT_LEN),
                    sanitizeTime(item.getPublishedTime()),
                    truncate(result.text(), SNIPPET_REF_LEN)));
        }
        metrics.recordGuardDropped(dropped);
        if (guarded.isEmpty()) {
            log.warn("联网来源全部被注入防护丢弃 | candidates={}, dropped={}", urlDeduped.size(), dropped);
            return WebAugmentResult.unusable("all_dropped");
        }

        // ③ 语义去重：跨站转载只留排序靠前的一条
        int beforeDedup = guarded.size();
        List<WebSource> deduped = semanticDeduplicator.dedup(guarded, WebSource::text);
        metrics.recordDedupDropped(beforeDedup - deduped.size());

        // ④ 截断到上下文条数上限
        List<WebSource> picked = deduped.size() > Math.max(1, contextTopN)
                ? deduped.subList(0, Math.max(1, contextTopN))
                : deduped;

        log.info("联网预检索完成 | queries={}, merged={}, urlDedup={}, guardDropped={}, dedup={}, used={}, cost={}ms",
                effective, merged.size(), urlDeduped.size(), dropped, beforeDedup - deduped.size(),
                picked.size(), System.currentTimeMillis() - start);
        return new WebAugmentResult(true, List.copyOf(picked), "ok");
    }

    /**
     * 组装注入主模型的【全网实时引用】上下文块。
     *
     * <p>与本地来源块刻意形态不同：
     * <ul>
     *   <li>用 {@code <untrusted_web_content>} 隔离，并前置安全声明（外部内容零信任）；</li>
     *   <li>显式标明来源是"全网实时检索"，<b>不许</b>模型把它说成用户笔记；</li>
     *   <li>标注时间线：有发布时间就写出来，没有就统一标注「本轮实时检索」——
     *       这样"本地旧笔记 vs 全网最新"的冲突在提示词层面就有可比维度。</li>
     * </ul>
     *
     * @param startIndex 起始编号（本地来源占 1..n，网页从 n+1 起，保证与正文角标一致）
     */
    public String buildContext(List<WebSource> sources, int startIndex) {
        if (sources == null || sources.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("【全网实时引用】以下是你刚刚联网检索到的实时网页摘要（共 ")
                .append(sources.size()).append(" 条，编号从 ").append(startIndex).append(" 起）。\n");
        sb.append(WebContentGuard.defensiveInstruction()).append("\n\n");

        for (int i = 0; i < sources.size(); i++) {
            WebSource source = sources.get(i);
            int index = startIndex + i;
            String timeLabel = source.publishedTime() != null && !source.publishedTime().isBlank()
                    ? "发布于 " + source.publishedTime()
                    : "本轮实时检索";
            sb.append(webContentGuard.wrap(index, source.title(), source.url(), source.text()));
            sb.append("\n时效标注：[").append(index).append("] ").append(timeLabel).append("\n\n");
        }
        return sb.toString().stripTrailing();
    }

    /**
     * 生成与外网来源对应的统一溯源结构（{@code sourceType=WEB_SEARCH}）。
     * 与 {@link #buildContext} 必须用<b>同一个 startIndex</b>，否则正文角标与引用卡片会错位。
     */
    public List<SourceReference> toReferences(List<WebSource> sources, int startIndex) {
        if (sources == null || sources.isEmpty()) {
            return List.of();
        }
        List<SourceReference> references = new ArrayList<>();
        for (int i = 0; i < sources.size(); i++) {
            WebSource source = sources.get(i);
            references.add(SourceReference.web(startIndex + i, source.title(), source.url(), source.snippet()));
        }
        return references;
    }

    private List<String> normalizeQueries(List<String> queries) {
        if (queries == null) {
            return List.of();
        }
        return queries.stream()
                .filter(q -> q != null && !q.isBlank())
                .map(String::trim)
                .distinct()
                .limit(Math.max(1, maxQueries))
                .toList();
    }

    /** 单条搜索的局部降级：MCP 未配置 / 网络抖动都只影响这一路 */
    private List<WebSearchResultVO> safeQuery(String keyword) {
        try {
            return webSearchService.query(keyword, perQueryLimit);
        } catch (Exception e) {
            log.warn("联网预检索单路失败 | keyword={}", keyword, e);
            return List.of();
        }
    }

    /** 发布时间是外部可控文本，只保留日期常见字符，防注入我们自己的标记结构 */
    private String sanitizeTime(String time) {
        if (time == null || time.isBlank()) {
            return null;
        }
        String cleaned = time.replaceAll("[^0-9\\-/:年月日时分TZ.+ ]", "").trim();
        if (cleaned.isEmpty()) {
            return null;
        }
        return cleaned.length() > 32 ? cleaned.substring(0, 32) : cleaned;
    }

    private String truncate(String text, int maxLen) {
        if (text == null) {
            return "";
        }
        String clean = text.replaceAll("\\s+", " ").trim();
        return clean.length() > maxLen ? clean.substring(0, maxLen) + "..." : clean;
    }

    /**
     * 单条网页来源（已过防护与去重）。
     *
     * @param title         网页标题
     * @param url           链接（统一溯源的主键）
     * @param text          注入上下文用的正文（已消毒，≤600 字）
     * @param publishedTime 发布时间；为 null 表示上游未提供 → 上下文按「本轮实时检索」标注
     * @param snippet       落库/展示用的短片段（≤120 字）
     */
    public record WebSource(String title, String url, String text,
                            String publishedTime, String snippet) {
    }

    /**
     * @param usable  本次是否取到可用内容
     * @param sources 结构化网页来源（index 未分配，由编排层统一编号）
     * @param reason  不可用原因：no_query / empty_result / mcp_timeout / all_dropped / ok
     */
    public record WebAugmentResult(boolean usable, List<WebSource> sources, String reason) {
        static WebAugmentResult unusable(String reason) {
            return new WebAugmentResult(false, List.of(), reason);
        }
    }
}
