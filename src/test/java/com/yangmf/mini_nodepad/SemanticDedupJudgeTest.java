package com.yangmf.mini_nodepad;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yangmf.mini_nodepad.mapper.UserSemanticCandidateMapper;
import com.yangmf.mini_nodepad.pojo.dto.ExtractedHabit;
import com.yangmf.mini_nodepad.pojo.entity.UserSemanticCandidate;
import com.yangmf.mini_nodepad.service.SemanticMemoryService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 方案 B（宽松向量召回 + LLM 仲裁）路由行为验证 —— 打真 Qdrant / 真 MySQL / 真 LLM。
 * <p>
 * 核心命题：**形式相似 ≠ 事实同一**，向量负责召回、LLM 负责裁决。
 * 场景刻意构造两组对照，正是纯阈值方案无法区分的那两组：
 * <ul>
 *   <li>同类别不同取值（Vue3 vs React）→ 必须各自独立成行，绝不能合并；</li>
 *   <li>同义复述（Vue3 两种说法 / 禁注释两种说法）→ 必须并入同一行累加 hit_count。</li>
 * </ul>
 * 期望终态：候选池恰好 3 行，Vue3 行 hit=2、React 行 hit=1、禁注释行 hit=2。
 * <p>
 * 使用独立 user_id=999，避免污染 demo(3) 的真实记忆数据；Qdrant 侧按 user_id 过滤天然隔离。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.rabbitmq.listener.simple.auto-startup=false",
                "spring.rabbitmq.listener.direct.auto-startup=false"
        })
@Slf4j
class SemanticDedupJudgeTest {

    /** 测试专用 user_id（与 demo 的 3 隔离） */
    private static final Long USER_ID = 999L;

    private static final String FACT_VUE3_1 = "用户偏好使用 Vue3 进行前端开发";
    private static final String FACT_VUE3_2 = "用户偏好用 Vue3 写前端代码";
    private static final String FACT_REACT = "用户偏好使用 React 进行前端开发";
    private static final String FACT_NO_COMMENT_1 = "用户禁止在代码中添加注释";
    private static final String FACT_NO_COMMENT_2 = "用户习惯不要在代码里写多余的注释";

    @Autowired
    private SemanticMemoryService semanticMemoryService;

    @Autowired
    private UserSemanticCandidateMapper candidateMapper;

    @BeforeEach
    void cleanTestRows() {
        candidateMapper.delete(new LambdaQueryWrapper<UserSemanticCandidate>()
                .eq(UserSemanticCandidate::getUserId, USER_ID));
    }

    @Test
    void looseRecallPlusLlmJudge() {
        System.out.println("\n" + "=".repeat(92));
        System.out.println("方案 B 路由验证：宽松召回(0.60, limit=3) + LLM 仲裁");
        System.out.println("=".repeat(92));

        runWindow("窗口1  Vue3 首现", FACT_VUE3_1, "PREFERENCE");
        runWindow("窗口2  React（同类别不同取值 → 绝不该合并）", FACT_REACT, "PREFERENCE");
        runWindow("窗口3  Vue3 同义改写（该并入窗口1）", FACT_VUE3_2, "PREFERENCE");
        runWindow("窗口4  禁注释首现", FACT_NO_COMMENT_1, "CONSTRAINT");
        runWindow("窗口5  禁注释同义改写（该并入窗口4）", FACT_NO_COMMENT_2, "CONSTRAINT");

        List<UserSemanticCandidate> rows = candidateMapper.selectList(
                new LambdaQueryWrapper<UserSemanticCandidate>()
                        .eq(UserSemanticCandidate::getUserId, USER_ID));

        System.out.println("\n" + "-".repeat(92));
        System.out.println("终态候选池（" + rows.size() + " 行）：");
        for (UserSemanticCandidate c : rows) {
            System.out.printf("  hit=%s promoted=%s rule=%-10s fact=%s%n",
                    c.getHitCount(), c.getPromoted(), c.getRuleType(), c.getFact());
        }

        int vue3Hit = hitOf(rows, FACT_VUE3_1);
        int reactHit = hitOf(rows, FACT_REACT);
        int commentHit = hitOf(rows, FACT_NO_COMMENT_1);

        System.out.println("\n判定：");
        System.out.printf("  候选行数      = %d  （期望 3：Vue3 / React / 禁注释 各一行）%n", rows.size());
        System.out.printf("  Vue3   hit    = %d  （期望 2：首现 + 同义改写）%n", vue3Hit);
        System.out.printf("  React  hit    = %d  （期望 1：与 Vue3 独立，未被误合并）%n", reactHit);
        System.out.printf("  禁注释 hit    = %d  （期望 2：首现 + 同义改写）%n", commentHit);

        assertEquals(3, rows.size(), "候选行数应为 3（Vue3 / React / 禁注释）");
        assertEquals(2, vue3Hit, "Vue3 同义改写应并入首现行");
        assertEquals(1, reactHit, "React 必须独立成行，不得被 Vue3 误合并");
        assertEquals(2, commentHit, "禁注释同义改写应并入首现行");

        System.out.println("\n  ✅ PASS：宽松召回 + LLM 仲裁 正确区分「同义复述」与「同类不同取值」");
        System.out.println("=".repeat(92) + "\n");
    }

    private void runWindow(String title, String fact, String ruleType) {
        System.out.println("\n>>> " + title + "  | fact=" + fact);
        semanticMemoryService.processAndPromote(USER_ID,
                List.of(new ExtractedHabit(fact, ruleType)));
        List<UserSemanticCandidate> rows = candidateMapper.selectList(
                new LambdaQueryWrapper<UserSemanticCandidate>()
                        .eq(UserSemanticCandidate::getUserId, USER_ID));
        System.out.println("    当前候选池 " + rows.size() + " 行：");
        for (UserSemanticCandidate c : rows) {
            System.out.printf("      hash=%s… hit=%s fact=%s%n",
                    c.getFactHash().substring(0, 8), c.getHitCount(), c.getFact());
        }
    }

    private int hitOf(List<UserSemanticCandidate> rows, String factPrefix) {
        return rows.stream()
                .filter(r -> r.getFact() != null && r.getFact().startsWith(factPrefix))
                .map(UserSemanticCandidate::getHitCount)
                .findFirst()
                .orElse(-1);
    }
}
