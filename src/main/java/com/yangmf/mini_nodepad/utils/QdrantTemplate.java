package com.yangmf.mini_nodepad.utils;

import com.yangmf.mini_nodepad.encoder.Bm25SparseEncoder;
import com.yangmf.mini_nodepad.encoder.Bm25SparseEncoder.SparseVector;
import com.yangmf.mini_nodepad.exception.BusinessException;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
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

import static io.qdrant.client.ConditionFactory.*;
import static io.qdrant.client.QueryFactory.nearest;
import static io.qdrant.client.QueryFactory.rrf;
import static io.qdrant.client.ValueFactory.value;

import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ExecutionException;

@Slf4j
@Component
public class QdrantTemplate {

    private final EmbeddingModel embeddingModel;
    private final QdrantClient qdrantClient;
    private final Bm25SparseEncoder bm25Encoder;

    private static final String DENSE_VECTOR_NAME = "";
    private static final String SPARSE_VECTOR_NAME = "sparse";

    @Value("${qdrant.dense.score-threshold:0.30}")
    private float denseScoreThreshold;

    @Value("${qdrant.sparse.score-threshold:1.0}")
    private float sparseScoreThreshold;

    public QdrantTemplate(EmbeddingModel embeddingModel, QdrantClient qdrantClient, Bm25SparseEncoder bm25Encoder) {
        this.embeddingModel = embeddingModel;
        this.qdrantClient = qdrantClient;
        this.bm25Encoder = bm25Encoder;
    }

    public void upsert(String collectionName, String pointId, String textToEmbed, String rawText, Map<String, Object> payloads) {
        if (textToEmbed == null || textToEmbed.trim().isEmpty()) {
            log.warn("写入向量库失败，文本为空 | collection={}", collectionName);
            return;
        }

        try {
            Response<Embedding> embedResponse = embeddingModel.embed(textToEmbed);
            if (embedResponse == null || embedResponse.content() == null) {
                throw new BusinessException("Embedding 模型未返回有效结果");
            }
            float[] denseArray = embedResponse.content().vector();
            List<Float> denseVectorList = new ArrayList<>(denseArray.length);
            for (float v : denseArray) {
                denseVectorList.add(v);
            }

            SparseVector sparseData = bm25Encoder.encodeDocument(textToEmbed);

            Map<String, io.qdrant.client.grpc.JsonWithInt.Value> qdrantPayload = new HashMap<>();
            if (payloads != null) {
                payloads.forEach((k, v) -> {
                    io.qdrant.client.grpc.JsonWithInt.Value val = toQdrantValue(v);
                    if (val != null) { qdrantPayload.put(k, val); }
                });
            }
            qdrantPayload.put("rawText", value(rawText != null ? rawText : textToEmbed));

            PointStruct point = PointStruct.newBuilder()
                    .setId(Common.PointId.newBuilder().setUuid(toQdrantUuid(pointId)).build())
                    .putAllPayload(qdrantPayload)
                    .setVectors(Vectors.newBuilder()
                            .setVectors(NamedVectors.newBuilder()
                                    .putVectors(DENSE_VECTOR_NAME, Vector.newBuilder().addAllData(denseVectorList).build())
                                    .putVectors(SPARSE_VECTOR_NAME, Vector.newBuilder()
                                            .setSparse(Points.SparseVector.newBuilder()
                                                    .addAllIndices(sparseData.indices())
                                                    .addAllValues(sparseData.values()).build())
                                            .build())
                                    .build())
                            .build())
                    .build();

            qdrantClient.upsertAsync(collectionName, Collections.singletonList(point)).get();
            log.info("成功写入向量库 | collection={}, pointId={}", collectionName, pointId);

        } catch (ExecutionException e) {
            log.error("写入向量库失败 | collection={}, pointId={}", collectionName, pointId, e);
            throw new BusinessException("向量库写入失败，请稍后重试", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("写入向量库被中断 | collection={}, pointId={}", collectionName, pointId, e);
            throw new BusinessException("向量库写入被中断，请稍后重试", e);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("写入向量库未知异常 | collection={}, pointId={}", collectionName, pointId, e);
            throw new BusinessException("向量库写入失败，请稍后重试", e);
        }
    }

    public List<VectorSearchResult> search(String collectionName, String queryText, Map<String, Object> exactFilters, int limit) {
        try {
            Filter.Builder filterBuilder = Filter.newBuilder();
            if (exactFilters != null && !exactFilters.isEmpty()) {
                exactFilters.forEach((key, val) -> {
                    if (val instanceof String) {
                        filterBuilder.addMust(matchKeyword(key, (String) val));
                    } else if (val instanceof Integer || val instanceof Long) {
                        filterBuilder.addMust(match(key, ((Number) val).longValue()));
                    } else if (val instanceof List<?> rawList && !rawList.isEmpty()) {
                        List<String> stringValues = rawList.stream()
                                .map(String::valueOf)
                                .toList();
                        filterBuilder.addMust(matchKeywords(key, stringValues));
                    }
                });
            }
            Filter combinedFilter = filterBuilder.build();

            Response<Embedding> embedResponse = embeddingModel.embed(queryText);
            if (embedResponse == null || embedResponse.content() == null) {
                throw new BusinessException("Embedding 模型未返回有效结果");
            }
            float[] queryDenseArray = embedResponse.content().vector();

            PrefetchQuery densePrefetch = PrefetchQuery.newBuilder()
                    .setQuery(nearest(io.qdrant.client.VectorInputFactory.vectorInput(queryDenseArray)))
                    .setUsing(DENSE_VECTOR_NAME)
                    .setFilter(combinedFilter)
                    .setLimit(limit * 2)
                    .setScoreThreshold(denseScoreThreshold)
                    .build();

            SparseVector querySparseData = bm25Encoder.encodeQuery(queryText);
            PrefetchQuery sparsePrefetch = PrefetchQuery.newBuilder()
                    .setQuery(nearest(io.qdrant.client.grpc.Points.VectorInput.newBuilder()
                            .setSparse(Points.SparseVector.newBuilder()
                                    .addAllIndices(querySparseData.indices())
                                    .addAllValues(querySparseData.values()).build()).build()))
                    .setUsing(SPARSE_VECTOR_NAME)
                    .setFilter(combinedFilter)
                    .setLimit(limit * 2)
                    .setScoreThreshold(sparseScoreThreshold)
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
                if (point.getScore() <= 0.0f) {
                    continue;
                }

                Map<String, Object> returnPayload = new HashMap<>();
                point.getPayloadMap().forEach((k, v) -> returnPayload.put(k, extractValue(v)));

                String originalChunkId = (String) returnPayload.get("chunkId");
                results.add(VectorSearchResult.builder()
                        .pointId(originalChunkId != null ? originalChunkId : point.getId().getUuid())
                        .score(point.getScore())
                        .payload(returnPayload)
                        .rawText((String) returnPayload.get("rawText"))
                        .build());
            }
            return results;

        } catch (ExecutionException e) {
            log.error("Qdrant RRF 检索失败 | collection={}", collectionName, e);
            throw new BusinessException("向量检索服务暂时不可用，请稍后重试", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Qdrant RRF 检索被中断 | collection={}", collectionName, e);
            throw new BusinessException("向量检索被中断，请稍后重试", e);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Qdrant RRF 检索未知异常 | collection={}", collectionName, e);
            throw new BusinessException("向量检索失败，请稍后重试", e);
        }
    }

    public void delete(String collectionName, String pointId) {
        try {
            Common.PointId id = Common.PointId.newBuilder().setUuid(toQdrantUuid(pointId)).build();
            qdrantClient.deleteAsync(collectionName, Collections.singletonList(id)).get();
            log.info("已从向量库删除 | collection={}, pointId={}", collectionName, pointId);
        } catch (ExecutionException e) {
            log.error("删除向量库内容失败 | collection={}, pointId={}", collectionName, pointId, e);
            throw new BusinessException("向量库删除失败，请稍后重试", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("删除向量库被中断 | collection={}, pointId={}", collectionName, pointId, e);
            throw new BusinessException("向量库删除被中断，请稍后重试", e);
        } catch (Exception e) {
            log.error("删除向量库未知异常 | collection={}, pointId={}", collectionName, pointId, e);
            throw new BusinessException("向量库删除失败，请稍后重试", e);
        }
    }

    public void deleteByPageId(String collectionName, String pageId) {
        try {
            Filter filter = Filter.newBuilder()
                    .addMust(matchKeyword("pageId", pageId))
                    .build();

            qdrantClient.deleteAsync(collectionName, filter).get();
            log.info("已从向量库删除 pageId 的所有 Chunk | collection={}, pageId={}", collectionName, pageId);
        } catch (ExecutionException e) {
            log.error("批量删除 Chunk 失败 | collection={}, pageId={}", collectionName, pageId, e);
            throw new BusinessException("向量库批量删除失败，请稍后重试", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("批量删除 Chunk 被中断 | collection={}, pageId={}", collectionName, pageId, e);
            throw new BusinessException("向量库批量删除被中断，请稍后重试", e);
        } catch (Exception e) {
            log.error("批量删除 Chunk 未知异常 | collection={}, pageId={}", collectionName, pageId, e);
            throw new BusinessException("向量库批量删除失败，请稍后重试", e);
        }
    }

    public List<String> fetchChunkRawTextsByPageId(String collectionName, String pageId) {
        try {
            QueryPoints.Builder queryBuilder = QueryPoints.newBuilder()
                    .setCollectionName(collectionName)
                    .setLimit(10000)
                    .setWithPayload(WithPayloadSelector.newBuilder().setEnable(true).build());

            if (pageId != null) {
                Filter filter = Filter.newBuilder()
                        .addMust(matchKeyword("pageId", pageId))
                        .build();
                queryBuilder.setFilter(filter);
            }

            List<ScoredPoint> points = qdrantClient.queryAsync(queryBuilder.build()).get();
            List<String> rawTexts = new ArrayList<>();

            for (ScoredPoint point : points) {
                io.qdrant.client.grpc.JsonWithInt.Value val = point.getPayloadMap().get("rawText");
                if (val != null && val.hasStringValue()) {
                    rawTexts.add(val.getStringValue());
                }
            }

            log.debug("Qdrant 取回 chunk 文本 | count={}, pageId={}", rawTexts.size(), pageId != null ? pageId : "ALL");
            return rawTexts;
        } catch (ExecutionException e) {
            log.error("Qdrant 查询 chunk 文本失败 | pageId={}", pageId, e);
            throw new BusinessException("向量库查询失败，请稍后重试", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Qdrant 查询 chunk 文本被中断 | pageId={}", pageId, e);
            throw new BusinessException("向量库查询被中断，请稍后重试", e);
        } catch (Exception e) {
            log.error("Qdrant 查询 chunk 文本未知异常 | pageId={}", pageId, e);
            throw new BusinessException("向量库查询失败，请稍后重试", e);
        }
    }

    // ================= 私有辅助方法 =================

    private String toQdrantUuid(String semanticId) {
        return UUID.nameUUIDFromBytes(semanticId.getBytes(StandardCharsets.UTF_8)).toString();
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

    @Data
    @Builder
    public static class VectorSearchResult {
        private String pointId;
        private float score;
        private String rawText;
        private Map<String, Object> payload;
    }
}