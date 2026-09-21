package com.yangmf.mini_nodepad.ai.rag;

import com.huaban.analysis.jieba.JiebaSegmenter;
import com.huaban.analysis.jieba.SegToken;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
public class Bm25SparseEncoder {

    private static final JiebaSegmenter SEGMENTER = new JiebaSegmenter();

    private static final float K1 = 1.5f;
    private static final float B = 0.75f;

    private static final String REDIS_DF_KEY = "bm25:df:table";
    private static final String REDIS_N_KEY = "bm25:total:docs";
    private static final String REDIS_TOKENS_KEY = "bm25:total:tokens";
    private static final String REDIS_DRIFT_KEY = "bm25:drift:counter";

    @Value("${bm25.redis-pipeline-batch-size:500}")
    private int redisPipelineBatchSize;

    private final StringRedisTemplate redisTemplate;
    private final Executor aiTaskExecutor;

    private final ConcurrentHashMap<Integer, AtomicInteger> documentFrequency = new ConcurrentHashMap<>();
    private final AtomicInteger totalDocuments = new AtomicInteger(0);
    private final AtomicLong totalTokenCount = new AtomicLong(0);
    private final AtomicInteger driftCounter = new AtomicInteger(0);

    public Bm25SparseEncoder(StringRedisTemplate redisTemplate,
                             @Qualifier("aiTaskExecutor") Executor aiTaskExecutor) {
        this.redisTemplate = redisTemplate;
        this.aiTaskExecutor = aiTaskExecutor;
    }
    @PostConstruct
    public void loadFromRedis() {
        try {
            String nStr = redisTemplate.opsForValue().get(REDIS_N_KEY);
            if (nStr != null) {
                totalDocuments.set(Integer.parseInt(nStr));
            }

            String tokensStr = redisTemplate.opsForValue().get(REDIS_TOKENS_KEY);
            if (tokensStr != null) {
                totalTokenCount.set(Long.parseLong(tokensStr));
            }

            String driftStr = redisTemplate.opsForValue().get(REDIS_DRIFT_KEY);
            if (driftStr != null) {
                driftCounter.set(Integer.parseInt(driftStr));
            }

            Map<Object, Object> dfEntries = redisTemplate.opsForHash().entries(REDIS_DF_KEY);
            dfEntries.forEach((k, v) -> {
                int termIndex = Integer.parseInt(k.toString());
                int df = Integer.parseInt(v.toString());
                documentFrequency.put(termIndex, new AtomicInteger(df));
            });

            log.info("BM25 状态已恢复: docs={}, tokens={}, vocab_size={}, drift={}",
                    totalDocuments.get(), totalTokenCount.get(), documentFrequency.size(), driftCounter.get());
        } catch (DataAccessException e) {
            log.error("BM25 Redis 连接异常，将从空表开始构建", e);
        } catch (NumberFormatException e) {
            log.error("BM25 Redis 数据格式损坏，将从空表开始构建，建议触发重建", e);
        } catch (Exception e) {
            log.warn("BM25 Redis 加载失败，将从空表开始构建", e);
        }
    }

    public SparseVector encodeDocument(String text) {
        if (text == null || text.isBlank()) {
            return SparseVector.empty();
        }

        List<SegToken> tokens = SEGMENTER.process(text, JiebaSegmenter.SegMode.SEARCH);

        Map<Integer, Integer> termFreqMap = new HashMap<>();
        int validTokenCount = 0;

        for (SegToken token : tokens) {
            String word = token.word.trim();
            if (word.length() < 2) {
                continue;
            }
            validTokenCount++;
            int termIndex = mapToIndex(word);
            termFreqMap.merge(termIndex, 1, Integer::sum);
        }

        if (validTokenCount == 0) {
            return SparseVector.empty();
        }

        totalDocuments.incrementAndGet();
        totalTokenCount.addAndGet(validTokenCount);

        Map<String, String> redisDfIncrements = new HashMap<>(termFreqMap.size());

        for (int termIndex : termFreqMap.keySet()) {
            documentFrequency
                    .computeIfAbsent(termIndex, k -> new AtomicInteger(0))
                    .incrementAndGet();
            redisDfIncrements.put(String.valueOf(termIndex), "1");
        }

        asyncUpdateRedis(validTokenCount, redisDfIncrements, true);

        float avgDocLength = getAverageDocLength();
        List<Integer> indices = new ArrayList<>(termFreqMap.size());
        List<Float> values = new ArrayList<>(termFreqMap.size());

        for (Map.Entry<Integer, Integer> entry : termFreqMap.entrySet()) {
            int termIndex = entry.getKey();
            int tf = entry.getValue();

            float bm25Weight = computeBm25(tf, validTokenCount, avgDocLength, termIndex);
            if (bm25Weight > 0) {
                indices.add(termIndex);
                values.add(bm25Weight);
            }
        }

        return new SparseVector(indices, values);
    }

    /**
     * 移除单个文档的 BM25 统计贡献（内存 + Redis 异步递减）
     * 同时累加漂移计数器（持久化到 Redis）
     */
    public void removeDocument(String text) {
        if (text == null || text.isBlank()) {
            return;
        }

        List<SegToken> tokens;
        try {
            tokens = SEGMENTER.process(text, JiebaSegmenter.SegMode.SEARCH);
        } catch (Exception e) {
            log.warn("BM25 removeDocument 分词失败，跳过本次递减 (textLen={})", text.length(), e);
            return;
        }

        Set<Integer> uniqueTerms = new HashSet<>();
        int validTokenCount = 0;

        for (SegToken token : tokens) {
            String word = token.word.trim();
            if (word.length() < 2) {
                continue;
            }
            validTokenCount++;
            uniqueTerms.add(mapToIndex(word));
        }

        if (validTokenCount == 0) {
            return;
        }

        totalDocuments.decrementAndGet();
        totalTokenCount.addAndGet(-validTokenCount);

        Map<String, String> redisDfDecrements = new HashMap<>(uniqueTerms.size());
        for (int termIndex : uniqueTerms) {
            AtomicInteger counter = documentFrequency.get(termIndex);
            if (counter != null) {
                int newVal = counter.decrementAndGet();
                if (newVal <= 0) {
                    documentFrequency.remove(termIndex);
                }
                redisDfDecrements.put(String.valueOf(termIndex), "1");
            }
        }

        asyncUpdateRedis(validTokenCount, redisDfDecrements, false);

        int newDrift = driftCounter.incrementAndGet();
        asyncPersistDriftCounter(newDrift);

        log.debug("BM25 已递减: docs={}, tokens={}, drift={}",
                totalDocuments.get(), totalTokenCount.get(), newDrift);
    }

    /**
     * 从完整的 chunk 文本列表全量重建 BM25 统计（内存 + Redis 整体覆写）
     * 重建完成后漂移计数器归零
     */
    public void rebuildFromChunks(List<String> allChunkTexts) {
        if (allChunkTexts == null) {
            throw new IllegalArgumentException("chunkTexts 不能为 null");
        }

        log.info("BM25 全量重建开始，共 {} 个 chunk", allChunkTexts.size());

        ConcurrentHashMap<Integer, AtomicInteger> newDf = new ConcurrentHashMap<>();
        int newTotalDocs = 0;
        long newTotalTokens = 0;
        int skippedCount = 0;

        for (String text : allChunkTexts) {
            if (text == null || text.isBlank()) {
                skippedCount++;
                continue;
            }

            List<SegToken> tokens;
            try {
                tokens = SEGMENTER.process(text, JiebaSegmenter.SegMode.SEARCH);
            } catch (Exception e) {
                log.warn("BM25 重建跳过异常 chunk (textLen={})", text.length(), e);
                skippedCount++;
                continue;
            }

            Set<Integer> uniqueTerms = new HashSet<>();
            int validTokenCount = 0;

            for (SegToken token : tokens) {
                String word = token.word.trim();
                if (word.length() < 2) {
                    continue;
                }
                validTokenCount++;
                uniqueTerms.add(mapToIndex(word));
            }

            if (validTokenCount == 0) {
                skippedCount++;
                continue;
            }

            newTotalDocs++;
            newTotalTokens += validTokenCount;

            for (int termIndex : uniqueTerms) {
                newDf.computeIfAbsent(termIndex, k -> new AtomicInteger(0)).incrementAndGet();
            }
        }

        documentFrequency.clear();
        documentFrequency.putAll(newDf);
        totalDocuments.set(newTotalDocs);
        totalTokenCount.set(newTotalTokens);

        flushFullStateToRedis(newDf, newTotalDocs, newTotalTokens);

        resetDriftCounter();

        log.info("BM25 全量重建完成: docs={}, tokens={}, vocab_size={}, skipped={}",
                newTotalDocs, newTotalTokens, newDf.size(), skippedCount);
    }

    public SparseVector encodeQuery(String text) {
        if (text == null || text.isBlank()) {
            return SparseVector.empty();
        }

        List<SegToken> tokens = SEGMENTER.process(text, JiebaSegmenter.SegMode.SEARCH);

        Map<Integer, Integer> termFreqMap = new LinkedHashMap<>();
        for (SegToken token : tokens) {
            String word = token.word.trim();
            if (word.length() < 2) {
                continue;
            }
            int termIndex = mapToIndex(word);
            termFreqMap.merge(termIndex, 1, Integer::sum);
        }

        int queryLength = termFreqMap.values().stream().mapToInt(Integer::intValue).sum();
        boolean dfCold = totalDocuments.get() == 0;

        List<Integer> indices = new ArrayList<>();
        List<Float> values = new ArrayList<>();

        for (Map.Entry<Integer, Integer> entry : termFreqMap.entrySet()) {
            int termIndex = entry.getKey();
            int tf = entry.getValue();

            AtomicInteger dfCounter = documentFrequency.get(termIndex);

            float bm25Weight;
            if (dfCounter == null && dfCold) {
                bm25Weight = 1.0f + (float) tf / queryLength;
            } else if (dfCounter == null) {
                continue;
            } else {
                bm25Weight = computeBm25(tf, queryLength, (float) queryLength, termIndex);
            }

            if (bm25Weight > 0) {
                indices.add(termIndex);
                values.add(bm25Weight);
            }
        }

        if (dfCold && !indices.isEmpty()) {
            log.debug("BM25 冷启动降级: DF 表为空，查询使用词重叠权重 (terms={})", indices.size());
        }

        return new SparseVector(indices, values);
    }

    public void reset() {
        int oldVocabSize = documentFrequency.size();
        documentFrequency.clear();
        totalDocuments.set(0);
        totalTokenCount.set(0);
        resetDriftCounter();
        try {
            redisTemplate.delete(List.of(REDIS_DF_KEY, REDIS_N_KEY, REDIS_TOKENS_KEY, REDIS_DRIFT_KEY));
        } catch (DataAccessException e) {
            log.error("BM25 Redis 清理失败，内存已重置但 Redis 可能残留旧数据", e);
        }
        log.info("BM25 统计已重置 (oldVocab={}, docs=0)", oldVocabSize);
    }

    // ==================== 查询方法（供 Service 层编排决策） ====================

    public int getTotalDocuments() {
        return totalDocuments.get();
    }

    public int getVocabularySize() {
        return documentFrequency.size();
    }

    public int getDriftCount() {
        return driftCounter.get();
    }

    /**
     * 重置漂移计数器（内存 + Redis）
     */
    public void resetDriftCounter() {
        driftCounter.set(0);
        asyncPersistDriftCounter(0);
    }

    // ==================== 私有方法 ====================

    private float computeBm25(int tf, int docLength, float avgDocLength, int termIndex) {
        float idf = getIdf(termIndex);
        float tfNorm = (tf * (K1 + 1)) / (tf + K1 * (1 - B + B * docLength / avgDocLength));
        return idf * tfNorm;
    }

    private float getIdf(int termIndex) {
        AtomicInteger dfCounter = documentFrequency.get(termIndex);
        int df = (dfCounter != null) ? dfCounter.get() : 0;
        int n = totalDocuments.get();
        if (n == 0 || df == 0) {
            return 0;
        }
        return (float) Math.log(1.0 + (n - df + 0.5) / (df + 0.5));
    }

    private float getAverageDocLength() {
        int n = totalDocuments.get();
        if (n == 0) {
            return 1.0f;
        }
        return (float) totalTokenCount.get() / n;
    }

    private void asyncUpdateRedis(int tokensDelta, Map<String, String> dfDeltas, boolean increment) {
        aiTaskExecutor.execute(() -> {
            try {
                redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                    byte[] keyBytes = redisTemplate.getStringSerializer().serialize(REDIS_DF_KEY);
                    long delta = increment ? 1L : -1L;

                    dfDeltas.forEach((k, v) -> {
                        byte[] field = redisTemplate.getStringSerializer().serialize(k);
                        connection.hashCommands().hIncrBy(keyBytes, field, delta);
                    });

                    byte[] nKeyBytes = redisTemplate.getStringSerializer().serialize(REDIS_N_KEY);
                    byte[] tokensKeyBytes = redisTemplate.getStringSerializer().serialize(REDIS_TOKENS_KEY);

                    if (increment) {
                        connection.stringCommands().incr(nKeyBytes);
                        connection.stringCommands().incrBy(tokensKeyBytes, tokensDelta);
                    } else {
                        connection.stringCommands().decr(nKeyBytes);
                        connection.stringCommands().decrBy(tokensKeyBytes, tokensDelta);
                    }
                    return null;
                });
            } catch (DataAccessException e) {
                log.warn("BM25 Redis {} 失败 (内存统计仍准确，下次重启可恢复)",
                        increment ? "增量更新" : "递减更新", e);
            }
        });
    }

    private void asyncPersistDriftCounter(int value) {
        aiTaskExecutor.execute(() -> {
            try {
                redisTemplate.opsForValue().set(REDIS_DRIFT_KEY, String.valueOf(value));
            } catch (DataAccessException e) {
                log.warn("BM25 漂移计数器 Redis 持久化失败 (内存值={}, 下次重启可恢复)", value, e);
            }
        });
    }

    private void flushFullStateToRedis(ConcurrentHashMap<Integer, AtomicInteger> newDf,
                                       int newTotalDocs, long newTotalTokens) {
        try {
            redisTemplate.delete(List.of(REDIS_DF_KEY, REDIS_N_KEY, REDIS_TOKENS_KEY, REDIS_DRIFT_KEY));
        } catch (DataAccessException e) {
            log.warn("BM25 Redis 旧数据清理失败，新数据将覆盖写入", e);
        }

        try {
            List<Map.Entry<Integer, AtomicInteger>> entries = new ArrayList<>(newDf.entrySet());

            for (int i = 0; i < entries.size(); i += redisPipelineBatchSize) {
                int end = Math.min(i + redisPipelineBatchSize, entries.size());
                List<Map.Entry<Integer, AtomicInteger>> batch = entries.subList(i, end);

                redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                    byte[] keyBytes = redisTemplate.getStringSerializer().serialize(REDIS_DF_KEY);
                    for (Map.Entry<Integer, AtomicInteger> entry : batch) {
                        byte[] field = redisTemplate.getStringSerializer().serialize(String.valueOf(entry.getKey()));
                        byte[] val = redisTemplate.getStringSerializer().serialize(String.valueOf(entry.getValue().get()));
                        connection.hashCommands().hSet(keyBytes, field, val);
                    }
                    return null;
                });
            }

            redisTemplate.opsForValue().set(REDIS_N_KEY, String.valueOf(newTotalDocs));
            redisTemplate.opsForValue().set(REDIS_TOKENS_KEY, String.valueOf(newTotalTokens));
        } catch (DataAccessException e) {
            log.error("BM25 Redis 全量刷盘失败，内存数据正确但 Redis 可能不完整", e);
        }
    }

    private static int mapToIndex(String word) {
        int h = word.hashCode();
        h ^= h >>> 16;
        h *= 0x85ebca6b;
        h ^= h >>> 13;
        h *= 0xc2b2ae35;
        h ^= h >>> 16;
        return h & 0x7fffffff;
    }

    public record SparseVector(List<Integer> indices, List<Float> values) {
        public static SparseVector empty() {
            return new SparseVector(List.of(), List.of());
        }
    }
}