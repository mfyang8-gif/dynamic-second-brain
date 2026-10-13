package com.yangmf.mini_nodepad.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yangmf.mini_nodepad.ai.aiservice.HabitJudge;
import com.yangmf.mini_nodepad.ai.component.PromptSanitizer;
import com.yangmf.mini_nodepad.ai.rag.QdrantTemplate;
import com.yangmf.mini_nodepad.enums.SemanticRuleType;
import com.yangmf.mini_nodepad.exception.ForbiddenException;
import com.yangmf.mini_nodepad.mapper.UserSemanticCandidateMapper;
import com.yangmf.mini_nodepad.mapper.UserSemanticMemoryMapper;
import com.yangmf.mini_nodepad.pojo.dto.ExtractedHabit;
import com.yangmf.mini_nodepad.pojo.dto.HabitVerdict;
import com.yangmf.mini_nodepad.pojo.entity.UserSemanticCandidate;
import com.yangmf.mini_nodepad.pojo.entity.UserSemanticMemory;
import com.yangmf.mini_nodepad.pojo.vo.SemanticHabitVO;
import com.yangmf.mini_nodepad.service.SemanticMemoryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 全局语义记忆晋升中枢（Phase 3 终极版：**宽松向量召回 + LLM 仲裁** + 双库联动）。
 * <p>
 * 为什么查重分两级（方案 B）：
 * 纯向量阈值判定不成立——余弦衡量的是**句子形式相似度**，不是**事实同一性**。实测（text-embedding-v4）：
 * 「用中文交流 vs 用英文交流」= 0.9330、「偏好 Vue3 vs 偏好 React」= 0.8473，而真正该合并的
 * 「不要在代码里写多余的注释 vs 禁止在代码中添加注释」只有 0.7483。即「该合并的最低分」低于
 * 「绝不该合并的最高分」，**不存在任何可用的相似度阈值**（降阈值只会把改口的习惯吞掉）。
 * 因此：向量只做**宽松召回**（0.60，保证同义句不漏），是否同一条交 {@link HabitJudge} 裁决。
 * <p>
 * 双库分工：MySQL 是 source of truth（计数/晋升/遗忘权），Qdrant user_semantic_habits
 * 集合仅作语义召回索引——向量缺失或冗余都不产生数据错误，下个扫描窗口自愈。
 * <p>
 * 刻意不加 @Transactional：查重链路含 Embedding HTTP（超时配到 120s）、Qdrant gRPC 与 LLM 调用，
 * 包进事务会长时间霸占 Hikari 连接（池上限 5）拖垮全站；一致性由三重幂等保障：
 * uk_user_fact 阻断重复行、fact_hash 确定性生成（同文本恒同值）、向量 upsert 按确定性
 * pointId 覆盖写。单条失败仅丢该条累加，绝不产生脏数据。
 * <p>
 * userId 一律由调用方显式传入（MQ 消费线程无 HTTP 上下文，禁止触碰 BaseContext）。
 */
@Slf4j
@Service
public class SemanticMemoryServiceImpl implements SemanticMemoryService {

    /** 晋升门槛：独立会话窗口命中次数（B 裁决：去重后 3 次 ≈ 跨会话 3 次） */
    private static final int PROMOTION_THRESHOLD = 3;

    /** 候选池 TTL：30 天未再命中且未晋升的记录懒清理，防候选池无限膨胀 */
    private static final int CANDIDATE_TTL_DAYS = 30;

    /** 注入 Top-N 截断（缺口 2 裁决）：每类最多注入 10 条，防 Token 膨胀与注意力稀释 */
    private static final int MAX_INJECT_PER_TYPE = 10;

    /** LLM 仲裁时单条候选原文的展示上限（防超长文本撑爆上下文/夹带注入指令） */
    private static final int JUDGE_SNIPPET_CHARS = 120;

    /** matchedHash 前缀容错匹配的最小长度：低于此长度不足以唯一定位，直接拒绝 */
    private static final int HASH_PREFIX_MIN = 8;

    private final UserSemanticCandidateMapper candidateMapper;
    private final UserSemanticMemoryMapper memoryMapper;
    private final PromptSanitizer promptSanitizer;
    private final QdrantTemplate qdrantTemplate;
    private final HabitJudge habitJudge;

    /** 方案 B：本值语义已从「判定阈值」变为「召回阈值」，必须放宽（默认 0.60）保证同义句不漏 */
    @Value("${semantic.dedup.similarity-threshold:0.60}")
    private double recallThreshold;

    /** 宽松召回条数：只把最相近的 N 条喂给 LLM 仲裁，控制上下文窗口与判定噪音 */
    @Value("${semantic.dedup.recall-limit:3}")
    private int recallLimit;

    @Value("${semantic.dedup.collection-name:user_semantic_habits}")
    private String habitCollection;

    public SemanticMemoryServiceImpl(UserSemanticCandidateMapper candidateMapper,
                                     UserSemanticMemoryMapper memoryMapper,
                                     PromptSanitizer promptSanitizer,
                                     QdrantTemplate qdrantTemplate,
                                     HabitJudge habitJudge) {
        this.candidateMapper = candidateMapper;
        this.memoryMapper = memoryMapper;
        this.promptSanitizer = promptSanitizer;
        this.qdrantTemplate = qdrantTemplate;
        this.habitJudge = habitJudge;
    }

    @Override
    public void processAndPromote(Long userId, List<ExtractedHabit> habits) {
        if (userId == null || habits == null || habits.isEmpty()) {
            return;
        }

        // 1. 三道闸净化 + 批内精确 hash 去重：同文案先合并，节省 embedding 与 LLM 调用（盲区 G）
        Map<String, UserSemanticCandidate> batchDeduped = new LinkedHashMap<>();
        for (ExtractedHabit habit : habits) {
            SemanticRuleType ruleType = SemanticRuleType.parse(habit.ruleType());
            if (ruleType == null) {
                log.warn("非法 rule_type 拒收（白名单外视为投毒/幻觉） | userId={}, raw={}", userId, habit.ruleType());
                continue;
            }
            String fact = promptSanitizer.sanitizeFact(habit.fact());
            if (fact == null) {
                log.warn("fact 消毒后为空或超限拒收 | userId={}, raw={}", userId, truncate(habit.fact()));
                continue;
            }
            batchDeduped.computeIfAbsent(sha256Hex(fact),
                    hash -> UserSemanticCandidate.builder()
                            .fact(fact).ruleType(ruleType).build());
        }
        if (batchDeduped.isEmpty()) {
            return;
        }

        // 2. 宽松召回 → LLM 仲裁 → 双库联动计数（方案 B 核心）。
        //    touchedHashes 保障裁决盲区 B 语义不因查重而破功：同一窗口内两条"不同措辞、同一事实"
        //    的习惯先后提取时，第二条会被召回并裁决为相同 hash，若不去重将造成同窗口 +2 —— 本 Set 拦截。
        Set<String> touchedHashes = new HashSet<>();
        for (Map.Entry<String, UserSemanticCandidate> entry : batchDeduped.entrySet()) {
            String exactHash = entry.getKey();
            String fact = entry.getValue().getFact();
            SemanticRuleType ruleType = entry.getValue().getRuleType();

            // 命中历史习惯 → 复用旧 hash；全新 → 用自身 exactHash
            String finalHash = resolveFinalHash(userId, fact, exactHash);

            if (!touchedHashes.add(finalHash)) {
                log.info("语义变体本窗口已计数，跳过重复累加 | userId={}, fact={}", userId, truncate(fact));
                continue;
            }

            // MySQL 侧幂等累加：命中旧习惯按旧 hash +1；全新习惯按新 hash 落库 hit_count=1。
            // ON DUPLICATE 不更新 fact 文本与 rule_type，旧记录保留首次入库的标准化表述。
            candidateMapper.upsertHit(userId, finalHash, fact, ruleType);

            // 实际落到自身 hash 时写向量索引（全新习惯必写；召回 miss 但 MySQL 已有行时重写，
            // 顺带自愈"有行无向量"的漂移）。命中他人旧 hash 时不写——该向量本就在库里。
            if (finalHash.equals(exactHash)) {
                writeHabitVectorQuietly(userId, finalHash, fact, ruleType);
            }
        }

        // 3. TTL 懒清理：顺带扫除长期无人复现的陈年候选，降低晋升扫描负载。
        //    注意：被清理候选的向量残留在 Qdrant，下次查重命中后按旧 hash 重建 MySQL 行，自愈一致。
        int purged = candidateMapper.delete(new LambdaQueryWrapper<UserSemanticCandidate>()
                .eq(UserSemanticCandidate::getUserId, userId)
                .eq(UserSemanticCandidate::getPromoted, 0)
                .lt(UserSemanticCandidate::getLastHitAt, LocalDateTime.now().minusDays(CANDIDATE_TTL_DAYS)));
        if (purged > 0) {
            log.info("候选池 TTL 懒清理 | userId={}, purged={}", userId, purged);
        }

        // 4. 晋升维持不变：hit_count>=3 且未晋升 → INSERT IGNORE 正式表（UK 防竞态）→ 无条件打标
        //    （正式表已存在同 hash 含 deleted=1 时 IGNORE 静默跳过，打标防止本行陷入无限重扫）
        List<UserSemanticCandidate> eligible = candidateMapper.selectList(
                new LambdaQueryWrapper<UserSemanticCandidate>()
                        .eq(UserSemanticCandidate::getUserId, userId)
                        .eq(UserSemanticCandidate::getPromoted, 0)
                        .ge(UserSemanticCandidate::getHitCount, PROMOTION_THRESHOLD));
        for (UserSemanticCandidate candidate : eligible) {
            int inserted = memoryMapper.insertIgnorePromoted(
                    userId, candidate.getFactHash(), candidate.getFact(), candidate.getRuleType());
            candidateMapper.markPromoted(candidate.getId());
            if (inserted > 0) {
                log.info("习惯晋升至正式记忆库 | userId={}, ruleType={}, fact={}",
                        userId, candidate.getRuleType(), candidate.getFact());
            }
        }
    }

    @Override
    public Map<String, List<String>> getUserGlobalMemory(Long userId) {
        if (userId == null) {
            return Map.of();
        }
        // Top-N 截断（缺口 2）：每类按最近强化时间倒序取前 10，防重度用户 Token 膨胀；
        // rule_type 列是 varchar，eq 用 code 字符串绕开 wrapper 枚举参数 TypeHandler 分派的不确定性
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (SemanticRuleType type : SemanticRuleType.values()) {
            List<UserSemanticMemory> memories = memoryMapper.selectList(
                    new LambdaQueryWrapper<UserSemanticMemory>()
                            .eq(UserSemanticMemory::getUserId, userId)
                            .eq(UserSemanticMemory::getRuleType, type.getCode())
                            .orderByDesc(UserSemanticMemory::getLastReinforcedAt)
                            .last("LIMIT " + MAX_INJECT_PER_TYPE));
            List<String> facts = memories.stream()
                    .map(UserSemanticMemory::getFact)
                    .filter(f -> f != null && !f.isBlank())
                    .toList();
            if (!facts.isEmpty()) {
                result.put(type.getCode(), facts);
            }
        }
        return result;
    }

    @Override
    public List<SemanticHabitVO> listHabits(Long userId) {
        if (userId == null) {
            return List.of();
        }
        return memoryMapper.selectList(new LambdaQueryWrapper<UserSemanticMemory>()
                        .eq(UserSemanticMemory::getUserId, userId)
                        .orderByDesc(UserSemanticMemory::getLastReinforcedAt))
                .stream()
                .map(m -> SemanticHabitVO.builder()
                        .id(m.getId())
                        .fact(m.getFact())
                        .ruleType(m.getRuleType().getCode())
                        .lastReinforcedAt(m.getLastReinforcedAt())
                        .build())
                .toList();
    }

    @Override
    public void deleteHabit(Long userId, Long id) {
        // @TableLogic：已删/不存在均返回 null。不存在与非本人一律 403（红线 2：防资源探测）
        UserSemanticMemory memory = memoryMapper.selectById(id);
        if (memory == null || !memory.getUserId().equals(userId)) {
            throw new ForbiddenException("无权操作该资源");
        }
        // 逻辑删除 deleted=1（用户遗忘权）；候选行已 promoted=1 且查重只累加候选计数，
        // 已删习惯不会被后台扫描悄悄复活
        memoryMapper.deleteById(id);
        log.info("用户行使遗忘权删除习惯 | userId={}, habitId={}, fact={}", userId, id, truncate(memory.getFact()));
    }

    // ==================== 方案 B 查重私有链路 ====================

    /**
     * 查重路由总入口：宽松召回 → 命中自身则直接复用 → 否则交 LLM 仲裁 → 全 miss 则新建。
     *
     * @return 最终应计入的 fact_hash：命中历史习惯返回旧 hash，全新习惯返回 exactHash
     */
    private String resolveFinalHash(Long userId, String fact, String exactHash) {
        List<QdrantTemplate.VectorSearchResult> recalled = recallSimilarHabits(userId, fact);
        if (recalled.isEmpty()) {
            return exactHash;
        }

        // 召回集合里含自身（同文本重跑，或向量残留在库）→ 是确定性同一，无需消耗 LLM
        List<QdrantTemplate.VectorSearchResult> others = new ArrayList<>(recalled.size());
        for (QdrantTemplate.VectorSearchResult hit : recalled) {
            String hash = factHashOf(hit);
            if (hash == null) {
                continue;
            }
            if (hash.equals(exactHash)) {
                log.info("向量召回命中自身，直接复用 | userId={}, similarity={}, fact={}",
                        userId, hit.getScore(), truncate(fact));
                return exactHash;
            }
            others.add(hit);
        }
        if (others.isEmpty()) {
            return exactHash;
        }

        return judgeSameHabit(userId, fact, exactHash, others);
    }

    /**
     * 宽松召回：纯稠密余弦 top-K（limit=recallLimit，只过滤当前 user_id），按召回阈值截断。
     * {@code searchByDenseSimilarity} 的 score 就是真实余弦（不经 RRF 融合），可直接与阈值比较。
     * Qdrant / Embedding 任何异常 → 返回空列表，链路降级为「hash 精确去重 + 全新入库」，不阻断晋升。
     */
    private List<QdrantTemplate.VectorSearchResult> recallSimilarHabits(Long userId, String fact) {
        try {
            List<QdrantTemplate.VectorSearchResult> hits = qdrantTemplate.searchByDenseSimilarity(
                    habitCollection, fact, Map.of("user_id", userId), recallLimit);
            if (hits.isEmpty()) {
                return List.of();
            }
            List<QdrantTemplate.VectorSearchResult> qualified = new ArrayList<>(hits.size());
            for (QdrantTemplate.VectorSearchResult hit : hits) {
                if (hit.getScore() >= recallThreshold) {
                    qualified.add(hit);
                }
            }
            if (qualified.isEmpty()) {
                log.debug("习惯语义召回未过线 | userId={}, topScore={}, threshold={}",
                        userId, hits.get(0).getScore(), recallThreshold);
            } else {
                log.info("习惯语义宽松召回 | userId={}, recall={}, qualified={}, topScore={}, newFact={}",
                        userId, hits.size(), qualified.size(), hits.get(0).getScore(), truncate(fact));
            }
            return qualified;
        } catch (Exception e) {
            log.warn("习惯向量召回失败，本条降级 hash 精确路径 | userId={}, fact={}", userId, truncate(fact), e);
            return List.of();
        }
    }

    /**
     * LLM 仲裁：把新习惯与召回的 Top-K 候选原文打包给 {@link HabitJudge}，
     * 由它判「是否同一事实」——这正是向量做不到的事（形式相似 ≠ 事实同一）。
     * <p>
     * 任何不确定与异常一律返回 exactHash（走新建）：漏合并只是多一条记忆，
     * 误合并会把用户改口后的新习惯吞进旧记忆造成信息丢失，故宁可漏、不可误。
     */
    private String judgeSameHabit(Long userId, String fact, String exactHash,
                                  List<QdrantTemplate.VectorSearchResult> candidates) {
        try {
            HabitVerdict verdict = habitJudge.judge(fact, buildCandidatePrompt(candidates));

            if (verdict == null || !Boolean.TRUE.equals(verdict.isSame())) {
                log.info("LLM 裁决判定为新习惯 | userId={}, topScore={}, newFact={}",
                        userId, candidates.get(0).getScore(), truncate(fact));
                return exactHash;
            }

            String matched = resolveMatchedHash(verdict.matchedHash(), candidates);
            if (matched == null && candidates.size() == 1) {
                // isSame=true 但 hash 复制出错：候选唯一时可安全兜底（不存在张冠李戴的可能）
                matched = factHashOf(candidates.get(0));
            }
            if (matched == null) {
                log.warn("LLM 判定相同但 matchedHash 无法定位，退回新建 | userId={}, raw={}, newFact={}",
                        userId, verdict.matchedHash(), truncate(fact));
                return exactHash;
            }
            log.info("LLM 裁决判定为同一习惯 | userId={}, similarity={}, matchedHash={}, newFact={}",
                    userId, candidates.get(0).getScore(), matched, truncate(fact));
            return matched;
        } catch (Exception e) {
            log.warn("LLM 仲裁失败，本条按全新习惯入库（宁可漏合并，不可误合并） | userId={}, fact={}",
                    userId, truncate(fact), e);
            return exactHash;
        }
    }

    /** 把候选整理成 `[n] hash=... | 原文` 的紧凑清单：控制 token，并让 hash 便于被模型原样引用 */
    private String buildCandidatePrompt(List<QdrantTemplate.VectorSearchResult> candidates) {
        StringBuilder sb = new StringBuilder(candidates.size() * 96);
        for (int i = 0; i < candidates.size(); i++) {
            QdrantTemplate.VectorSearchResult hit = candidates.get(i);
            String text = hit.getRawText() == null ? "" : hit.getRawText();
            if (text.length() > JUDGE_SNIPPET_CHARS) {
                text = text.substring(0, JUDGE_SNIPPET_CHARS);
            }
            sb.append('[').append(i + 1).append("] hash=").append(factHashOf(hit))
                    .append(" | ").append(text).append('\n');
        }
        return sb.toString();
    }

    /**
     * 归一化并校验模型返回的 matchedHash：必须能**唯一**映射回召回集合中的某一条。
     * 依次尝试 (a) 忽略大小写的精确相等，(b) 长度 >= 8 的前缀匹配（模型截断 hash 的常见形态）。
     * 无法唯一定位一律返回 null —— 宁可不合并，也绝不允许把新习惯记到错误的旧习惯上。
     */
    private String resolveMatchedHash(String rawMatched, List<QdrantTemplate.VectorSearchResult> candidates) {
        if (rawMatched == null) {
            return null;
        }
        String wanted = rawMatched.trim();
        if (wanted.isEmpty() || "null".equalsIgnoreCase(wanted)) {
            return null;
        }
        for (QdrantTemplate.VectorSearchResult hit : candidates) {
            String hash = factHashOf(hit);
            if (hash != null && hash.equalsIgnoreCase(wanted)) {
                return hash;
            }
        }
        if (wanted.length() >= HASH_PREFIX_MIN) {
            String lower = wanted.toLowerCase(Locale.ROOT);
            String onlyMatch = null;
            int matches = 0;
            for (QdrantTemplate.VectorSearchResult hit : candidates) {
                String hash = factHashOf(hit);
                if (hash != null && hash.toLowerCase(Locale.ROOT).startsWith(lower)) {
                    onlyMatch = hash;
                    matches++;
                }
            }
            if (matches == 1) {
                return onlyMatch;
            }
        }
        return null;
    }

    private String factHashOf(QdrantTemplate.VectorSearchResult hit) {
        Object hashObj = hit.getPayload() == null ? null : hit.getPayload().get("fact_hash");
        return (hashObj instanceof String s && !s.isBlank()) ? s : null;
    }

    /**
     * 全新习惯写入查重向量索引。pointId 经 toQdrantUuid 确定性映射，重复写为幂等覆盖；
     * payload 携带 user_id（召回过滤维度）与 fact_hash（命中后回查 MySQL 的钥匙）。
     * <p>
     * ⚠️ pointId 必须带 **userId 命名空间**：Qdrant 点 ID 是对本参数做的确定性哈希，
     * 若只用 fact_hash，则两个**不同用户**说出措辞完全相同的习惯时会落到同一个点，
     * 后写者覆盖前者的 payload.user_id —— 前者此后按 user_id 过滤就召不回自己的向量，
     * 跨表达召回间歇失效（并在两人之间来回乒乓覆盖）。
     * payload 里的 fact_hash 仍存**纯 hash**，因为它是回查 MySQL 的键。
     */
    private void writeHabitVectorQuietly(Long userId, String factHash, String fact, SemanticRuleType ruleType) {
        try {
            qdrantTemplate.upsert(habitCollection, vectorPointId(userId, factHash), fact, fact,
                    Map.of("user_id", userId,
                            "fact_hash", factHash,
                            "rule_type", ruleType.getCode()));
        } catch (Exception e) {
            log.warn("习惯向量写入失败（MySQL 为准，下窗口自愈） | userId={}, factHash={}", userId, factHash, e);
        }
    }

    /** Qdrant 点 ID 的命名空间：userId + fact_hash，保证多租户下同措辞习惯互不覆盖 */
    private String vectorPointId(Long userId, String factHash) {
        return userId + ":" + factHash;
    }

    /** 规范化哈希：trim + 小写折叠；仅作向量 miss 后的新习惯标识与批内精确合并 */
    private String sha256Hex(String fact) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(fact.trim().toLowerCase(Locale.ROOT)
                    .getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : bytes) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > 50 ? text.substring(0, 50) + "..." : text;
    }
}
