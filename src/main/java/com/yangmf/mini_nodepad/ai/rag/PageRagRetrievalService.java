package com.yangmf.mini_nodepad.ai.rag;

import com.yangmf.mini_nodepad.ai.component.RerankService;
import com.yangmf.mini_nodepad.exception.BusinessException;
import com.yangmf.mini_nodepad.mapper.PageMapper;
import com.yangmf.mini_nodepad.properties.QdrantProperties;
import com.yangmf.mini_nodepad.ai.rag.QdrantTemplate.VectorSearchResult;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

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

        log.info("RAG 检索完成 | 命中 {} 个有效切片, bookId={}", chunks.size(), bookId);
        return chunks;
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
        sb.append("【重要指令】请严格基于以下提供的参考切片回答用户问题。如果参考切片的内容与用户的问题完全不相关（例如知识库是关于心理学，而用户问的是红烧肉做法），请直接回答：'知识库中未找到与您问题相关的信息'，严禁自行编造或强行关联。\n\n");

        for (RagChunk chunk : chunks) {
            sb.append("### 来源 [").append(chunk.getDisplayIndex()).append("] ");
            sb.append(chunk.getTitle() != null && !chunk.getTitle().isBlank() ? chunk.getTitle() : "参考切片");
            if (chunk.getChunkIndex() != null) {
                sb.append(" (段落 #").append(chunk.getChunkIndex()).append(")");
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
    }

    public record ProcessedResult(
            String contextString,
            List<String> sourcePageIds,
            List<RagChunk> rawChunks,
            boolean hasResults
    ) {
        public List<SourceReference> sourceReferences() {
            if (rawChunks == null) return List.of();
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

    public record SourceReference(
            int index,
            String title,
            String pageId,
            Integer chunkIndex,
            String textSnippet
    ) {}

    private static String truncate(String text, int maxLen) {
        if (text == null) return "";
        String clean = text.trim().replace("\n", " ");
        return clean.length() > maxLen ? clean.substring(0, maxLen) + "..." : clean;
    }
}