package com.yangmf.mini_nodepad.utils;

import com.huaban.analysis.jieba.JiebaSegmenter;
import com.huaban.analysis.jieba.SegToken;
import dev.langchain4j.model.embedding.EmbeddingModel;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Common;
import io.qdrant.client.grpc.Common.Filter;
import io.qdrant.client.grpc.Points;
import io.qdrant.client.grpc.Common.Match; // 修复 1：引入底层的 Match 类
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
import static io.qdrant.client.ConditionFactory.match; // 修复 1：引入底层的 match 方法
import static io.qdrant.client.QueryFactory.nearest;
import static io.qdrant.client.QueryFactory.rrf;
import static io.qdrant.client.ValueFactory.value;

import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

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

    private static final String DENSE_VECTOR_NAME = "";
    private static final String SPARSE_VECTOR_NAME = "sparse";
    private static final JiebaSegmenter SEGMENTER = new JiebaSegmenter();

    /**
     * 核心方法 1：向指定的 Collection 写入数据 (包含文本的稠密向量 + 稀疏向量 + 动态 Payload)
     *
     * @param collectionName 集合名称
     * @param pointId        唯一主键 (建议直接传入 MySQL 的 UUID)
     * @param textToEmbed    需要被向量化和分词的核心文本
     * @param payloads       附加的过滤或展示字段 (如 bookId, userId, timestamp)
     */
    public void upsert(String collectionName, String pointId, String textToEmbed, Map<String, Object> payloads) {
        if (textToEmbed == null || textToEmbed.trim().isEmpty()) {
            log.warn("写入向量库失败，文本为空. Collection: {}", collectionName);
            return;
        }

        try {
            // 1. 生成稠密向量 (LangChain4j)
            float[] denseArray = embeddingModel.embed(textToEmbed).content().vector();
            List<Float> denseVectorList = new ArrayList<>(denseArray.length);
            for (float v : denseArray) {
                denseVectorList.add(v);
            }

            // 2. 生成稀疏向量 (Jieba TF Hash)
            SparseVectorData sparseData = generateSparseVector(textToEmbed);
            // 3. 动态组装 Payload
            Map<String, io.qdrant.client.grpc.JsonWithInt.Value> qdrantPayload = new HashMap<>();
            if (payloads != null) {
                payloads.forEach((k, v) -> {
                    io.qdrant.client.grpc.JsonWithInt.Value val = toQdrantValue(v);
                    if (val != null) {qdrantPayload.put(k, val);};
                });
            }
            // 强制把原始文本也存进去，方便检索后直接展示
            qdrantPayload.put("rawText", value(textToEmbed));

            // 4. 构建 Qdrant Point
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

            // 5. 异步写入并阻塞等待完成
            qdrantClient.upsertAsync(collectionName, Collections.singletonList(point)).get();
            log.info(" 成功写入向量库 [{}], PointId: {}", collectionName, pointId);

        } catch (Exception e) {
            log.error(" 写入向量库 [{}] 失败, PointId: {}", collectionName, pointId, e);
            throw new RuntimeException("Qdrant Upsert Error", e);
        }
    }

    /**
     * 核心方法 2：RRF 混合检索 (Dense + Sparse)
     *
     * @param collectionName 集合名称
     * @param queryText      用户搜索的文本
     * @param exactFilters   精确过滤条件 (例如：限制只搜某本书的笔记 bookId="xxx")
     * @param limit          返回条数
     * @return 统一的检索结果列表
     */
    public List<VectorSearchResult> search(String collectionName, String queryText, Map<String, Object> exactFilters, int limit) {
        try {
            // 1. 构建动态 Filter
            Filter.Builder filterBuilder = Filter.newBuilder();
            if (exactFilters != null && !exactFilters.isEmpty()) {
                exactFilters.forEach((key, val) -> {
                    if (val instanceof String) {
                        filterBuilder.addMust(matchKeyword(key, (String) val));
                    } else if (val instanceof Integer || val instanceof Long) {
                        //  修复 1：使用底层 gRPC 原生的 Match 构建器来匹配整数
                        filterBuilder.addMust(match(key, ((Number) val).longValue()));
                    }
                });
            }
            Filter combinedFilter = filterBuilder.build();

            // 2. 构建稠密查询 (Dense)
            float[] queryDenseArray = embeddingModel.embed(queryText).content().vector();
            PrefetchQuery densePrefetch = PrefetchQuery.newBuilder()
                    .setQuery(nearest(io.qdrant.client.VectorInputFactory.vectorInput(queryDenseArray)))
                    .setUsing(DENSE_VECTOR_NAME)
                    .setFilter(combinedFilter)
                    .setLimit(limit * 2) // 扩大预取池以提升 RRF 融合精度
                    .build();

            // 3. 构建稀疏查询 (Sparse)
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

            // 4. 发起 RRF 融合检索
            QueryPoints rrfQuery = QueryPoints.newBuilder()
                    .setCollectionName(collectionName)
                    .addPrefetch(densePrefetch)
                    .addPrefetch(sparsePrefetch)
                    .setQuery(rrf(Rrf.newBuilder().build()))
                    .setLimit(limit)
                    .setWithPayload(WithPayloadSelector.newBuilder().setEnable(true).build())
                    .build();

            List<ScoredPoint> qdrantResults = qdrantClient.queryAsync(rrfQuery).get();

            // 5. 格式化返回结果
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
            log.error(" Qdrant RRF 检索失败, Collection: {}", collectionName, e);
            return Collections.emptyList();
        }
    }

    /**
     * 核心方法 3：根据 Point ID 物理删除向量
     */
    public void delete(String collectionName, String pointId) {
        try {
            //  修复 2：适配新版 API，直接传入 List<PointId>
            Common.PointId id = Common.PointId.newBuilder().setUuid(pointId).build();
            qdrantClient.deleteAsync(collectionName, Collections.singletonList(id)).get();
            log.info("🗑️ 已从向量库 [{}] 删除 Point: {}", collectionName, pointId);
        } catch (Exception e) {
            log.error(" 删除向量库内容失败", e);
        }
    }

    // ================= 私有辅助方法 =================

    /**
     * 将文本转为稀疏向量 (基于 Jieba 和 Hash TF)
     */
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
            } // 过滤无意义单字
            int index = word.hashCode() & 0x7fffffff;
            tfMap.put(index, tfMap.getOrDefault(index, 0f) + 1.0f);
        }

        for (Map.Entry<Integer, Float> entry : tfMap.entrySet()) {
            indices.add(entry.getKey());
            values.add(entry.getValue());
        }
        return new SparseVectorData(indices, values);
    }

    /**
     * 将 Java 数据类型安全地转为 Qdrant Value 类型
     */
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
        return value(obj.toString()); // Added braces for clarity
    }

    /**
     * 将 Qdrant Value 类型还原为 Java 对象
     */
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

    // ================= 内部类 =================

    @RequiredArgsConstructor
    private static class SparseVectorData {
        final List<Integer> indices;
        final List<Float> values;
    }

    /**
     * 通用向量搜索返回体
     */
    @Data
    @Builder
    public static class VectorSearchResult {
        private String pointId;       // 对应 MySQL 里的真实 ID
        private float score;          // 混合检索综合得分
        private String rawText;       // 存入的原始核心文本
        private Map<String, Object> payload; // 其他元数据 (bookId, userId 等)
    }
}