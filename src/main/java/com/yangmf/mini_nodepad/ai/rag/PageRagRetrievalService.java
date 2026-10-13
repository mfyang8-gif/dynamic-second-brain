package com.yangmf.mini_nodepad.ai.rag;

import com.yangmf.mini_nodepad.ai.component.RerankService;
import com.yangmf.mini_nodepad.exception.BusinessException;
import com.yangmf.mini_nodepad.mapper.PageMapper;
import com.yangmf.mini_nodepad.pojo.entity.Page;
import com.yangmf.mini_nodepad.properties.QdrantProperties;
import com.yangmf.mini_nodepad.ai.rag.QdrantTemplate.VectorSearchResult;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class PageRagRetrievalService {

    private final QdrantTemplate qdrantTemplate;
    private final QdrantProperties qdrantProperties;
    private final PageMapper pageMapper;
    private final RerankService rerankService;

    private static final String DEFAULT_COLLECTION_NAME = "dynamic_brain";

    /** 本地来源时效标注格式：只精确到月 —— 笔记的"日"级更新对裁决没有意义，反而制造精度假象 */
    private static final DateTimeFormatter SOURCE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM");

    @Value("${rag.max-results:5}")
    private int maxResults;

    @Value("${rag.min-score-threshold:0.05}")
    private float minScoreThreshold;

    @Value("${rag.rerank.enabled:true}")
    private boolean rerankEnabled;

    @Value("${rag.rerank.chunk-threshold:500}")
    private int rerankChunkThreshold;

    @Value("${rag.rerank.candidate-multiplier:3}")
    private int rerankCandidateMultiplier;

    public PageRagRetrievalService(QdrantTemplate qdrantTemplate,
                                   QdrantProperties qdrantProperties,
                                   PageMapper pageMapper,
                                   RerankService rerankService) {
        this.qdrantTemplate = qdrantTemplate;
        this.qdrantProperties = qdrantProperties;
        this.pageMapper = pageMapper;
        this.rerankService = rerankService;
    }

    public List<RagChunk> retrieveChunks(String userQuery, String bookId,
                                         List<String> pageIds, int maxResults) {
        if (userQuery == null || userQuery.isBlank()) {
            return List.of();
        }

        Map<String, Object> filters = new HashMap<>();
        filters.put("bookId", bookId);
        if (pageIds != null && !pageIds.isEmpty()) {
            filters.put("pageId", pageIds);
        }

        log.debug("RAG 检索请求 | bookId={}, pageFilterCount={}, query={}",
                bookId, pageIds != null ? pageIds.size() : 0, userQuery);

        int totalChunks = getTotalChunks(bookId);
        boolean shouldRerank = rerankEnabled && totalChunks >= rerankChunkThreshold;
        int retrievalLimit = shouldRerank ? maxResults * rerankCandidateMultiplier : maxResults;

        log.info("RAG 检索策略 | bookId={}, totalChunks={}, rerank={}, retrievalLimit={}",
                bookId, totalChunks, shouldRerank, retrievalLimit);

        String collectionName = DEFAULT_COLLECTION_NAME;
        List<VectorSearchResult> searchResults;

        try {
            searchResults = qdrantTemplate.search(collectionName, userQuery, filters, retrievalLimit);
        } catch (Exception e) {
            log.error("Qdrant 向量检索异常 | bookId={}, collection={}", bookId, collectionName, e);
            throw new BusinessException("RAG 知识检索失败，基础设施异常", e);
        }

        if (searchResults == null || searchResults.isEmpty()) {
            log.info("RAG 检索无命中 | bookId={}, query={}", bookId, userQuery);
            return List.of();
        }

        if (log.isDebugEnabled()) {
            log.debug("RAG 原始分数分布 | query={}, results=[{}]",
                    userQuery,
                    searchResults.stream()
                            .map(r -> String.format("#%d score=%.4f text=%.30s",
                                    searchResults.indexOf(r) + 1, r.getScore(),
                                    r.getRawText() != null ? r.getRawText().replace("\n", " ") : "null"))
                            .collect(Collectors.joining(" | "))
            );
        }

        List<RagChunk> chunks = new ArrayList<>();
        int displayIndex = 1;

        for (VectorSearchResult hit : searchResults) {
            if (hit.getScore() < minScoreThreshold) {
                continue;
            }

            Map<String, Object> payload = hit.getPayload();
            if (payload == null) {
                payload = Collections.emptyMap();
            }

            chunks.add(RagChunk.builder()
                    .displayIndex(displayIndex++)
                    .text(hit.getRawText())
                    .pageId(str(payload.get("pageId")))
                    .bookId(str(payload.get("bookId")))
                    .title(str(payload.get("title")))
                    .summary(str(payload.get("summary")))
                    .chunkIndex(payload.get("chunkIndex") instanceof Number n ? n.intValue() : null)
                    .embeddingId(hit.getPointId())
                    .score(hit.getScore())
                    .build());
        }

        if (shouldRerank && chunks.size() > maxResults) {
            log.info("触发 Rerank | chunks={}, maxResults={}", chunks.size(), maxResults);
            chunks = rerankService.rerank(userQuery, chunks);
        }

        // 2026-10-12：补齐笔记更新时间（给主模型的时效裁决提供可比维度）
        fillUpdatedAt(chunks);

        log.info("RAG 检索完成 | 命中 {} 个有效切片, bookId={}", chunks.size(), bookId);
        return chunks;
    }

    /**
     * 用 chunk 的 pageId 批量回查 MySQL 补 {@code updatedAt}。
     *
     * <p>为什么不重建索引：Qdrant payload 里没有时间字段，但 {@code Page} 实体有两个时间列。
     * 每轮最多 5 条 chunk、涉及 1~5 个 pageId，一次 {@code selectBatchIds} 就是毫秒级 ——
     * 为这点信息重建整个向量库完全不划算。
     *
     * <p>失败静默：拿不到时间只会让主模型少一个裁决维度（退化成只看内容），
     * 绝不能因为补时间失败就让整轮检索挂掉。
     */
    private void fillUpdatedAt(List<RagChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        List<String> pageIds = chunks.stream()
                .map(RagChunk::getPageId)
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
        if (pageIds.isEmpty()) {
            return;
        }
        try {
            Map<String, LocalDateTime> updatedAtByPage = pageMapper.selectBatchIds(pageIds).stream()
                    .filter(p -> p.getId() != null && p.getUpdatedAt() != null)
                    .collect(Collectors.toMap(Page::getId, Page::getUpdatedAt, (a, b) -> a));
            chunks.forEach(c -> c.setUpdatedAt(updatedAtByPage.get(c.getPageId())));
        } catch (Exception e) {
            log.warn("回查笔记更新时间失败，本轮不带时效标注 | pageIds={}", pageIds, e);
        }
    }

    private int getTotalChunks(String bookId) {
        try {
            return pageMapper.sumChunkCountByBookId(bookId);
        } catch (Exception e) {
            log.warn("查询 chunk 总数失败，跳过 Rerank 判断 | bookId={}", bookId, e);
            return 0;
        }
    }

    public List<RagChunk> retrieveChunks(String userQuery, String bookId, List<String> pageIds) {
        return retrieveChunks(userQuery, bookId, pageIds, maxResults);
    }

    public List<RagChunk> retrieveChunks(String userQuery, String bookId) {
        return retrieveChunks(userQuery, bookId, null, maxResults);
    }

    public String buildContextString(List<RagChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        // 2026-10-12 双路并发后口径调整：本地来源不再是"唯一来源" —— 联网路可能同时提供
        // 【全网实时引用】。旧文案要求"不相关就只回一句没找到"，会在双路下直接掐掉联网那一路的答案。
        sb.append("【重要指令】以下是用户私有知识库中检索到的参考切片（可能与会话中的【全网实时引用】并行提供）。")
                .append("请优先依据与问题直接相关的内容作答；若这些切片与问题无关，")
                .append("不要强行关联、更不得据此编造，请按【来源权威性裁决】与其它来源交叉作答。\n\n");

        for (RagChunk chunk : chunks) {
            sb.append("### 来源 [").append(chunk.getDisplayIndex()).append("] ");
            sb.append(chunk.getTitle() != null && !chunk.getTitle().isBlank() ? chunk.getTitle() : "参考切片");
            if (chunk.getChunkIndex() != null) {
                sb.append(" (段落 #").append(chunk.getChunkIndex()).append(")");
            }
            // 时效标注（2026-10-12）：给模型一个可比较的维度，用于识别"笔记已旧"的冲突
            if (chunk.getUpdatedAt() != null) {
                sb.append(" · 笔记更新于 ").append(chunk.getUpdatedAt().format(SOURCE_TIME_FORMAT));
            }
            sb.append("\n");
            sb.append(chunk.getText() != null ? chunk.getText().trim() : "");
            sb.append("\n\n");
        }
        return sb.toString().stripTrailing();
    }

    public List<String> extractSourcePageIds(List<RagChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        return chunks.stream()
                .map(RagChunk::getPageId)
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .collect(Collectors.toList());
    }

    public ProcessedResult process(List<RagChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return ProcessedResult.empty();
        }
        // 2026-10-12 顺带修复：子查询拆分路径下多个 searchQuery 的结果是拼接去重的，
        // 各批次自己从 1 开始编号 —— 合并后 displayIndex 会重复，正文角标 [n] 与引用卡片编号对不上。
        // 这里统一按列表位置重排成 1..N，让 context 与 sourceReferences 严格一致。
        for (int i = 0; i < chunks.size(); i++) {
            chunks.get(i).setDisplayIndex(i + 1);
        }
        return new ProcessedResult(
                buildContextString(chunks),
                extractSourcePageIds(chunks),
                chunks,
                true
        );
    }

    private String str(Object obj) {
        return obj != null ? String.valueOf(obj) : null;
    }

    @Data
    @Builder
    public static class RagChunk {
        private int displayIndex;
        private String text;
        private String pageId;
        private String bookId;
        private String title;
        private String summary;
        private Integer chunkIndex;
        private String embeddingId;
        private float score;
        private Float rerankScore;
        /**
         * 所属笔记的最后更新时间（2026-10-12 新增）。
         *
         * <p>Qdrant payload 里没有时间字段，所以这个值不由向量检索返回 ——
         * 而是检索完成后用 {@code pageId} 批量回查 MySQL 补上（见 {@link #fillUpdatedAt}）。
         * 它让主模型在「本地旧笔记 vs 全网实时数据」冲突时有一个可比较的维度。
         */
        private LocalDateTime updatedAt;
    }

    public record ProcessedResult(
            String contextString,
            List<String> sourcePageIds,
            List<RagChunk> rawChunks,
            boolean hasResults
    ) {
        public List<SourceReference> sourceReferences() {
            if (rawChunks == null) {
                return List.of();
            }
            return rawChunks.stream()
                    .map(c -> new SourceReference(
                            c.getDisplayIndex(),
                            c.getTitle(),
                            c.getPageId(),
                            c.getChunkIndex(),
                            truncate(c.getText(), 80)
                    ))
                    .toList();
        }

        public static ProcessedResult empty() {
            return new ProcessedResult("", List.of(), List.of(), false);
        }
    }

    // SourceReference 已提升为本包顶层 record（同目录 SourceReference.java）——
    // 实体落库 / VO / TypeHandler 都要引用它，不该是检索服务的内部类。

    private static String truncate(String text, int maxLen) {
        if (text == null) {
            return "";
        }
        String clean = text.trim().replace("\n", " ");
        return clean.length() > maxLen ? clean.substring(0, maxLen) + "..." : clean;
    }
}