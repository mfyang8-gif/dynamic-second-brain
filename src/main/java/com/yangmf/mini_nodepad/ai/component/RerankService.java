package com.yangmf.mini_nodepad.ai.component;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.scoring.ScoringModel;
import com.yangmf.mini_nodepad.ai.rag.PageRagRetrievalService.RagChunk;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Slf4j
@Component
public class RerankService {

    private final ScoringModel scoringModel;

    @Value("${rag.rerank.top-n:5}")
    private int rerankTopN;

    public RerankService(ScoringModel scoringModel) {
        this.scoringModel = scoringModel;
    }

    public List<RagChunk> rerank(String query, List<RagChunk> candidates) {
        if (candidates == null || candidates.size() <= rerankTopN) {
            return candidates;
        }

        long start = System.currentTimeMillis();

        try {
            List<TextSegment> segments = candidates.stream()
                    .map(chunk -> TextSegment.from(chunk.getText() != null ? chunk.getText() : ""))
                    .toList();

            Response<List<Double>> response = scoringModel.scoreAll(segments, query);
            List<Double> scores = response.content();

            List<ScoredChunk> scored = new ArrayList<>();
            for (int i = 0; i < candidates.size(); i++) {
                double score = (scores != null && i < scores.size()) ? scores.get(i) : 0.0;
                scored.add(new ScoredChunk(candidates.get(i), (float) score));
            }

            List<ScoredChunk> topN = scored.stream()
                    .sorted(Comparator.comparingDouble(ScoredChunk::rerankScore).reversed())
                    .limit(rerankTopN)
                    .toList();

            List<RagChunk> reranked = new ArrayList<>();
            int displayIndex = 1;
            for (ScoredChunk s : topN) {
                reranked.add(RagChunk.builder()
                        .displayIndex(displayIndex++)
                        .text(s.chunk().getText())
                        .pageId(s.chunk().getPageId())
                        .bookId(s.chunk().getBookId())
                        .title(s.chunk().getTitle())
                        .summary(s.chunk().getSummary())
                        .chunkIndex(s.chunk().getChunkIndex())
                        .embeddingId(s.chunk().getEmbeddingId())
                        .score(s.chunk().getScore())
                        .rerankScore(s.rerankScore())
                        .build());
            }

            log.info("Rerank 完成 | input={}, output={}, cost={}ms",
                    candidates.size(), reranked.size(), System.currentTimeMillis() - start);
            return reranked;

        } catch (Exception e) {
            log.warn("Rerank 失败，降级使用原始 RRF 排序 | candidateCount={}", candidates.size(), e);
            return candidates.subList(0, Math.min(candidates.size(), rerankTopN));
        }
    }

    private record ScoredChunk(RagChunk chunk, float rerankScore) {}
}