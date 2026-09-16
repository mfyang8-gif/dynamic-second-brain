package com.yangmf.mini_nodepad.utils;

import com.huaban.analysis.jieba.JiebaSegmenter;
import com.huaban.analysis.jieba.SegToken;
import com.yangmf.mini_nodepad.exception.BusinessException;
import com.yangmf.mini_nodepad.properties.QdrantProperties;
import dev.langchain4j.model.embedding.EmbeddingModel;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Common;
import io.qdrant.client.grpc.Common.Filter;
import io.qdrant.client.grpc.Points;
import io.qdrant.client.grpc.Points.NamedVectors;
import io.qdrant.client.grpc.Points.PointStruct;
import io.qdrant.client.grpc.Points.PrefetchQuery;
import io.qdrant.client.grpc.Points.QueryPoints;
import io.qdrant.client.grpc.Points.Rrf;
import io.qdrant.client.grpc.Points.ScoredPoint;
import io.qdrant.client.grpc.Points.Vector;
import io.qdrant.client.grpc.Points.Vectors;
import io.qdrant.client.grpc.Points.WithPayloadSelector;

import static io.qdrant.client.ConditionFactory.matchKeyword;
import static io.qdrant.client.ConditionFactory.match;
import static io.qdrant.client.QueryFactory.nearest;
import static io.qdrant.client.QueryFactory.rrf;
import static io.qdrant.client.ValueFactory.value;

import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;

/**
 * 工业级 Qdrant 向量数据库通用模板类
 * 支持双向量 (稠密 + 稀疏) 写入与 RRF 混合检索
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QdrantTemplate {

    private final EmbeddingModel embeddingModel;
    private final QdrantClient qdrantClient;
    private final QdrantProperties qdrantProperties;

    private static final String DENSE_VECTOR_NAME = "";
    private static final String SPARSE_VECTOR_NAME = "sparse";
    private static final JiebaSegmenter SEGMENTER = new JiebaSegmenter();

    /**
     * 核心方法 1：向指定的 Collection 写入数据 (包含文本的稠密向量 + 稀疏向量 + 动态 Payload)
     */
    public void upsert(String collectionName, String pointId, String textToEmbed, String rawText, Map<String, Object> payloads) {
        if (textToEmbed == null || textToEmbed.trim().isEmpty()) {
            log.warn("写入向量库失败，文本为空. Collection: {}", collectionName);
            return;
        }

        try {
            float[] denseArray = embeddingModel.embed(textToEmbed).content().vector();
            List<Float> denseVectorList = new ArrayList<>(denseArray.length);
            for (float v : denseArray) {
                denseVectorList.add(v);
            }

            SparseVectorData sparseData = generateSparseVector(textToEmbed);

            Map<String, io.qdrant.client.grpc.JsonWithInt.Value> qdrantPayload = new HashMap<>();
            if (payloads != null) {
                payloads.forEach((k, v) -> {
                    io.qdrant.client.grpc.JsonWithInt.Value val = toQdrantValue(v);
                    if (val != null) { qdrantPayload.put(k, val); }
                });
            }
            qdrantPayload.put("rawText", value(rawText != null ? rawText : textToEmbed));

            PointStruct point = PointStruct.newBuilder()
                    .setId(Common.PointId.newBuilder().setUuid(pointId).build())
                    .putAllPayload(qdrantPayload)
                    .setVectors(Vectors.newBuilder()
                            .setVectors(NamedVectors.newBuilder()
                                    .putVectors(DENSE_VECTOR_NAME, Vector.newBuilder().addAllData(denseVectorList).build())
                                    .putVectors(SPARSE_VECTOR_NAME, Vector.newBuilder()
                                            .setSparse(Points.SparseVector.newBuilder()
                                                    .addAllIndices(sparseData.indices)
                                                    .addAllValues(sparseData.values).build())
                                            .build())
                                    .build())
                            .build())
                    .build();

            qdrantClient.upsertAsync(collectionName, Collections.singletonList(point)).get();
            log.info("成功写入向量库 [{}], PointId: {}", collectionName, pointId);

        } catch (Exception e) {
            log.error("写入向量库 [{}] 失败, PointId: {}", collectionName, pointId, e);
            //  抛出异常，让调用方（如异步线程）能够捕获并执行降级逻辑
            throw new BusinessException("Qdrant Upsert Error", e);
        }
    }

    /**
     * 核心方法 2：RRF 混合检索 (Dense + Sparse)
     */
    public List<VectorSearchResult> search(String collectionName, String queryText, Map<String, Object> exactFilters, int limit) {
        try {
            Filter.Builder filterBuilder = Filter.newBuilder();
            if (exactFilters != null && !exactFilters.isEmpty()) {
                exactFilters.forEach((key, val) -> {
                    if (val instanceof String) {
                        filterBuilder.addMust(matchKeyword(key, (String) val));
                    } else if (val instanceof Integer || val instanceof Long) {
                        filterBuilder.addMust(match(key, ((Number) val).longValue()));
                    }
                });
            }
            Filter combinedFilter = filterBuilder.build();

            float[] queryDenseArray = embeddingModel.embed(queryText).content().vector();
            PrefetchQuery densePrefetch = PrefetchQuery.newBuilder()
                    .setQuery(nearest(io.qdrant.client.VectorInputFactory.vectorInput(queryDenseArray)))
                    .setUsing(DENSE_VECTOR_NAME)
                    .setFilter(combinedFilter)
                    .setLimit(limit * 2)
                    .build();

            SparseVectorData querySparseData = generateSparseVector(queryText);
            PrefetchQuery sparsePrefetch = PrefetchQuery.newBuilder()
                    .setQuery(nearest(io.qdrant.client.grpc.Points.VectorInput.newBuilder()
                            .setSparse(Points.SparseVector.newBuilder()
                                    .addAllIndices(querySparseData.indices)
                                    .addAllValues(querySparseData.values).build()).build()))
                    .setUsing(SPARSE_VECTOR_NAME)
                    .setFilter(combinedFilter)
                    .setLimit(limit * 2)
                    .build();

            QueryPoints rrfQuery = QueryPoints.newBuilder()
                    .setCollectionName(collectionName)
                    .addPrefetch(densePrefetch)
                    .addPrefetch(sparsePrefetch)
                    .setQuery(rrf(Rrf.newBuilder().build()))
                    .setLimit(limit)
                    .setWithPayload(WithPayloadSelector.newBuilder().setEnable(true).build())
                    .build();

            List<ScoredPoint> qdrantResults = qdrantClient.queryAsync(rrfQuery).get();

            List<VectorSearchResult> results = new ArrayList<>();
            for (ScoredPoint point : qdrantResults) {
                Map<String, Object> returnPayload = new HashMap<>();
                point.getPayloadMap().forEach((k, v) -> returnPayload.put(k, extractValue(v)));

                results.add(VectorSearchResult.builder()
                        .pointId(point.getId().getUuid())
                        .score(point.getScore())
                        .payload(returnPayload)
                        .rawText((String) returnPayload.get("rawText"))
                        .build());
            }
            return results;

        } catch (Exception e) {
            log.error("Qdrant RRF 检索失败, Collection: {}", collectionName, e);
            //  抛出异常，防止上层误以为是“没有搜到数据”
            throw new BusinessException("Qdrant RRF Search Error", e);
        }
    }

    /**
     * 核心方法 3：根据 Point ID 物理删除单个向量
     */
    public void delete(String collectionName, String pointId) {
        try {
            Common.PointId id = Common.PointId.newBuilder().setUuid(pointId).build();
            qdrantClient.deleteAsync(collectionName, Collections.singletonList(id)).get();
            log.info("已从向量库 [{}] 删除 Point: {}", collectionName, pointId);
        } catch (Exception e) {
            log.error("删除向量库内容失败", e);
            //  抛出异常，确保业务事务（如 MySQL 删除）能够因一致性问题回滚或告警
            throw new BusinessException("Qdrant Delete Error", e);
        }
    }

    /**
     * 核心方法 4：根据 pageId 批量删除该笔记的所有 Chunk 向量
     */
    public void deleteByPageId(String collectionName, String pageId) {
        try {
            String url = String.format("http://%s:%d/collections/%s/points/delete",
                    qdrantProperties.getHost(), qdrantProperties.getHttpPort(), collectionName);

            String json = String.format("""
                    {"filter": {"must": [{"key": "pageId", "match": {"value": "%s"}}]}}
                    """, pageId);

            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                log.info("已删除向量库 [{}] 中 pageId={} 的所有 Chunk", collectionName, pageId);
            } else {
                String errorMsg = String.format("删除 Chunk 响应异常, collection: %s, pageId: %s, HTTP %d - %s",
                        collectionName, pageId, response.statusCode(), response.body());
                log.warn(errorMsg);
                // 🚀 HTTP 状态码非 200，说明删除失败，必须阻断流程
                throw new RuntimeException(errorMsg);
            }
        } catch (Exception e) {
            log.error("批量删除 Chunk 失败, collection: {}, pageId: {}", collectionName, pageId, e);
            // 🚀 捕获网络异常等，向上抛出
            throw new BusinessException("Qdrant Batch Delete Error", e);
        }
    }

    // ================= 私有辅助方法 =================


    private SparseVectorData generateSparseVector(String text) {
        List<Integer> indices = new ArrayList<>();
        List<Float> values = new ArrayList<>();
        if (text == null || text.trim().isEmpty()) {
            return new SparseVectorData(indices, values);
        }

        List<SegToken> tokens = SEGMENTER.process(text, JiebaSegmenter.SegMode.SEARCH);
        Map<Integer, Float> tfMap = new HashMap<>();

        for (SegToken token : tokens) {
            String word = token.word.trim();
            if (word.isEmpty() || word.length() < 2) {
                continue;
            }
            int index = word.hashCode() & 0x7fffffff;
            tfMap.put(index, tfMap.getOrDefault(index, 0f) + 1.0f);
        }

        for (Map.Entry<Integer, Float> entry : tfMap.entrySet()) {
            indices.add(entry.getKey());
            values.add(entry.getValue());
        }
        return new SparseVectorData(indices, values);
    }

    private io.qdrant.client.grpc.JsonWithInt.Value toQdrantValue(Object obj) {
        if (obj == null) {
            return null;
        }
        if (obj instanceof String) {
            return value((String) obj);
        }
        if (obj instanceof Integer) {
            return value((Integer) obj);
        }
        if (obj instanceof Long) {
            return value((Long) obj);
        }
        if (obj instanceof Double) {
            return value((Double) obj);
        }
        if (obj instanceof Float) {
            return value(((Float) obj).doubleValue());
        }
        if (obj instanceof Boolean) {
            return value((Boolean) obj);
        }
        return value(obj.toString());
    }

    private Object extractValue(io.qdrant.client.grpc.JsonWithInt.Value val) {
        if (val.hasStringValue()) {
            return val.getStringValue();
        }
        if (val.hasIntegerValue()) {
            return val.getIntegerValue();
        }
        if (val.hasDoubleValue()) {
            return val.getDoubleValue();
        }
        if (val.hasBoolValue()) {
            return val.getBoolValue();
        }
        return null;
    }

    @RequiredArgsConstructor
    private static class SparseVectorData {
        final List<Integer> indices;
        final List<Float> values;
    }

    @Data
    @Builder
    public static class VectorSearchResult {
        private String pointId;
        private float score;
        private String rawText;
        private Map<String, Object> payload;
    }
}