package com.yangmf.mini_nodepad;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yangmf.mini_nodepad.ai.rag.QdrantTemplate;
import com.yangmf.mini_nodepad.mapper.UserSemanticCandidateMapper;
import com.yangmf.mini_nodepad.pojo.entity.UserSemanticCandidate;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 习惯向量索引重建工具（一次性运维用，可保留亦可删除）。
 * <p>
 * 用途：Qdrant 的 user_semantic_habits 只是「可重建的查重索引」，MySQL 才是 source of truth。
 * 当索引发生漂移（写入失败、或历史版本 pointId 未带 userId 命名空间导致多租户互相覆盖）时，
 * 用本工具按 MySQL 的候选行把向量**全量补回**，无需改动任何计数。
 * <p>
 * 不修改 MySQL：只读候选行 → upsert 向量，幂等可重复执行。
 * 注意：跑之前建议先清空该集合的旧点位（旧格式 pointId 不含 userId 前缀，会与新点并存成重复）。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.rabbitmq.listener.simple.auto-startup=false",
                "spring.rabbitmq.listener.direct.auto-startup=false"
        })
@Slf4j
class SemanticVectorRebuildTest {

    private static final Long USER_ID = 3L;

    @Autowired
    private QdrantTemplate qdrantTemplate;

    @Autowired
    private UserSemanticCandidateMapper candidateMapper;

    @Value("${semantic.dedup.collection-name:user_semantic_habits}")
    private String habitCollection;

    @Test
    void rebuildVectorsFromMysql() {
        List<UserSemanticCandidate> rows = candidateMapper.selectList(
                new LambdaQueryWrapper<UserSemanticCandidate>()
                        .eq(UserSemanticCandidate::getUserId, USER_ID));

        System.out.println("\n" + "=".repeat(88));
        System.out.println("向量索引重建 | userId=" + USER_ID + " | 候选行 " + rows.size() + " 条");
        System.out.println("=".repeat(88));

        for (UserSemanticCandidate row : rows) {
            String pointId = USER_ID + ":" + row.getFactHash();
            qdrantTemplate.upsert(habitCollection, pointId, row.getFact(), row.getFact(),
                    Map.of("user_id", USER_ID,
                            "fact_hash", row.getFactHash(),
                            "rule_type", row.getRuleType().getCode()));
            System.out.printf("  已补写 | pointId=%s | fact=%s%n",
                    USER_ID + ":" + row.getFactHash().substring(0, 8) + "…", row.getFact());
        }

        assertTrue(rows.size() > 0, "候选行不应为空");
        System.out.println("\n  ✅ 重建完成：共 " + rows.size() + " 条向量");
        System.out.println("=".repeat(88) + "\n");
    }
}
