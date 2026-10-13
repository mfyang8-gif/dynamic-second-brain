package com.yangmf.mini_nodepad.ai.component;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * 网页来源语义去重（用户 2026-10-12 裁决：URL 级去重太粗）。
 *
 * <p>为什么 URL 去重不够：互联网大量内容是跨站抓取与转载的 —— 同一段文字出现在
 * 36kr / 新浪 / 微信公众号 / 个人博客上，URL 各不相同。只按 URL 去重会让主模型
 * 连吃三份同样的内容，白白消耗高阶模型的上下文窗口（用户在审计里点名的正是这条）。
 *
 * <p>两阶段（省成本的关键）：
 * <ol>
 *   <li><b>粗筛</b>（纯本地、毫秒级）：字符 3-gram 的 Jaccard 相似度 &gt; 0.85 → 疑似转载；</li>
 *   <li><b>精判</b>：只对疑似重复项调 text-embedding 算余弦，&gt; 0.92 → 判为重复，
 *       保留排序靠前（Rerank 分高）的那条。</li>
 * </ol>
 *
 * <p>为什么这个分层划算：<b>整段照搬式转载的字符重合度极高</b>，粗筛就能拦掉绝大多数；
 * embedding 只用来处理"改写型转载"（换标题、换段落顺序、同义替换），那部分数量少。
 * 所以平均成本 ≈ 零次 embedding 调用。
 */
@Slf4j
@Component
public class SemanticDeduplicator {

    private final EmbeddingModel embeddingModel;

    @Value("${web-search.dedup.enabled:true}")
    private boolean dedupEnabled;

    /** 粗筛阈值：字符 3-gram Jaccard。定得高一点，避免把"同话题不同文"误判成转载 */
    @Value("${web-search.dedup.coarse-threshold:0.85}")
    private double coarseThreshold;

    /** 精判阈值：embedding 余弦。改写型转载的判据 */
    @Value("${web-search.dedup.semantic-threshold:0.92}")
    private double semanticThreshold;

    /** 参与去重的文本截断长度：判"是不是同一篇"不需要全文 */
    private static final int DEDUP_TEXT_LEN = 400;

    /** 3-gram：中文粒度下 2-gram 太容易撞，4-gram 对短摘要太稀疏 */
    private static final int SHINGLE_SIZE = 3;

    public SemanticDeduplicator(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    /**
     * 顺序保留式去重：对输入列表逐条判断"是否与已保留项重复"。
     *
     * <p>调用方应先把候选按相关性（Rerank 分）降序排好 —— 本类保留"先出现的那条"，
     * 所以顺序即优先级。
     *
     * @param items      候选列表（已按优先级排序）
     * @param textGetter 取文本的函数（去重依据）
     * @return 去重后的列表；异常一律返回原列表（去重失败不该影响主链路）
     */
    public <T> List<T> dedup(List<T> items, Function<T, String> textGetter) {
        if (!dedupEnabled || items == null || items.size() <= 1) {
            return items == null ? List.of() : items;
        }

        try {
            List<T> kept = new ArrayList<>();
            List<Set<String>> keptShingles = new ArrayList<>();
            /** 已保留项的 embedding 惰性缓存（只在真的需要精判时才计算） */
            Map<Integer, float[]> keptVectors = new HashMap<>();
            int dropped = 0;

            for (T item : items) {
                String text = normalize(textGetter.apply(item));
                if (text.isEmpty()) {
                    kept.add(item);
                    keptShingles.add(Set.of());
                    continue;
                }
                Set<String> shingles = shingles(text);

                int suspected = -1;
                for (int i = 0; i < kept.size(); i++) {
                    double jaccard = jaccard(shingles, keptShingles.get(i));
                    if (jaccard >= coarseThreshold) {
                        suspected = i;
                        break;
                    }
                }

                if (suspected >= 0 && isSemanticDuplicate(text, suspected, kept, textGetter, keptVectors)) {
                    dropped++;
                    continue;
                }

                kept.add(item);
                keptShingles.add(shingles);
            }

            if (dropped > 0) {
                log.info("网页来源语义去重完成 | input={}, output={}, dropped={}",
                        items.size(), kept.size(), dropped);
            }
            return kept;
        } catch (Exception e) {
            log.warn("网页来源语义去重异常，保留全部候选 | size={}", items.size(), e);
            return items;
        }
    }

    /**
     * 精判：粗筛命中后才调用。任一侧 embedding 拿不到就保守判"不重复"（宁可多留一条，
     * 也不要把唯一来源误杀成空集）。
     */
    private <T> boolean isSemanticDuplicate(String text, int suspectedIndex, List<T> kept,
                                            Function<T, String> textGetter,
                                            Map<Integer, float[]> vectors) {
        float[] candidate = embed(text);
        if (candidate == null) {
            return false;
        }
        float[] reference = vectors.computeIfAbsent(suspectedIndex,
                i -> embed(normalize(textGetter.apply(kept.get(i)))));
        if (reference == null) {
            return false;
        }
        double cosine = cosine(candidate, reference);
        if (cosine >= semanticThreshold) {
            log.info("判定为跨站转载重复 | cosine={}, excerpt={}", String.format("%.4f", cosine), excerpt(text));
            return true;
        }
        return false;
    }

    private float[] embed(String text) {
        try {
            Response<Embedding> response = embeddingModel.embed(text);
            if (response == null || response.content() == null) {
                return null;
            }
            return response.content().vector();
        } catch (Exception e) {
            log.warn("去重精判 embedding 失败，跳过该次判重", e);
            return null;
        }
    }

    private static double cosine(float[] a, float[] b) {
        if (a.length != b.length) {
            return 0;
        }
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0 || nb == 0) {
            return 0;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    private static Set<String> shingles(String text) {
        Set<String> set = new HashSet<>();
        if (text.length() <= SHINGLE_SIZE) {
            set.add(text);
            return set;
        }
        for (int i = 0; i + SHINGLE_SIZE <= text.length(); i++) {
            set.add(text.substring(i, i + SHINGLE_SIZE));
        }
        return set;
    }

    private static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        // 小集合做外层，减少 contains 次数
        Set<String> smaller = a.size() <= b.size() ? a : b;
        Set<String> larger = smaller == a ? b : a;
        int inter = 0;
        for (String s : smaller) {
            if (larger.contains(s)) {
                inter++;
            }
        }
        int union = a.size() + b.size() - inter;
        return union == 0 ? 0 : (double) inter / union;
    }

    /** 归一化：只留实义字符，剔除空白、标点与数字噪声的影响 */
    private static String normalize(String text) {
        if (text == null) {
            return "";
        }
        String s = text.replaceAll("\\s+", "").replaceAll("[\\p{P}\\p{S}]", "");
        return s.length() > DEDUP_TEXT_LEN ? s.substring(0, DEDUP_TEXT_LEN) : s;
    }

    private static String excerpt(String text) {
        return text.length() > 40 ? text.substring(0, 40) + "..." : text;
    }
}
