package com.yangmf.mini_nodepad;

import com.yangmf.mini_nodepad.ai.aiservice.HabitExtractor;
import com.yangmf.mini_nodepad.ai.component.PromptSanitizer;
import com.yangmf.mini_nodepad.enums.SemanticRuleType;
import com.yangmf.mini_nodepad.mapper.UserSemanticCandidateMapper;
import com.yangmf.mini_nodepad.mapper.UserSemanticMemoryMapper;
import com.yangmf.mini_nodepad.pojo.dto.ExtractedHabit;
import com.yangmf.mini_nodepad.pojo.entity.UserSemanticCandidate;
import com.yangmf.mini_nodepad.pojo.entity.UserSemanticMemory;
import com.yangmf.mini_nodepad.service.SemanticMemoryService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

import java.util.List;

/**
 * 语义记忆链路诊断：一次性打印「生效密钥 → LLM 提取结果 → 落库结果」。
 * <p>
 * 目的：定位「幂等键已写、但候选行恒为 0」的静默失败点。
 * 注意：本测试会以 userId=3 真实写入 user_semantic_candidate（诊断用，属预期行为）。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.rabbitmq.listener.simple.auto-startup=false",
                "spring.rabbitmq.listener.direct.auto-startup=false"
        })
@Slf4j
class SemanticPipelineDiagnosticTest {

    private static final Long USER_ID = 3L;

    private static final String DIALOGUE =
            "1. 我平时开发习惯用 Vue3\n2. 以后前端相关的代码都按 Vue3 的方式来写\n";

    @Autowired
    private Environment env;

    @Autowired
    private HabitExtractor habitExtractor;

    @Autowired
    private SemanticMemoryService semanticMemoryService;

    @Autowired
    private PromptSanitizer promptSanitizer;

    @Autowired
    private UserSemanticCandidateMapper candidateMapper;

    @Autowired
    private UserSemanticMemoryMapper memoryMapper;

    private static String fp(String v) {
        if (v == null) {
            return "(null)";
        }
        if (v.isBlank()) {
            return "(blank/空串)";
        }
        return v.substring(0, Math.min(8, v.length())) + "…" + v.substring(v.length() - 4)
                + "(len=" + v.length() + ")";
    }

    @Test
    void diagnose() {
        System.out.println("\n" + "=".repeat(88));
        System.out.println("STEP 1 —— 生效密钥（证明 spring.config.import 的覆盖结果）");
        System.out.println("=".repeat(88));
        String[] keys = {
                "langchain4j.community.dashscope.chat-model.api-key",
                "langchain4j.open-ai.embedding-model.api-key",
                "langchain4j.open-ai.chat-model.api-key",
                "langchain4j.open-ai.streaming-chat-model.api-key",
                "rag.rerank.api-key",
                "semantic.dedup.similarity-threshold",
                "semantic.dedup.collection-name",
        };
        for (String k : keys) {
            System.out.printf("  %-52s %s%n", k, fp(env.getProperty(k)));
        }
        System.out.println("  active profiles = " + String.join(",", env.getActiveProfiles()));

        System.out.println("\n" + "=".repeat(88));
        System.out.println("STEP 2 —— 闸门单测（不经 LLM 的纯函数）");
        System.out.println("=".repeat(88));
        System.out.println("  SemanticRuleType.parse(\"PREFERENCE\") = " + SemanticRuleType.parse("PREFERENCE"));
        System.out.println("  SemanticRuleType.parse(\"preference\") = " + SemanticRuleType.parse("preference"));
        System.out.println("  sanitizeFact(标准句) = "
                + promptSanitizer.sanitizeFact("用户偏好使用 Vue3 进行前端开发"));
        System.out.println("  sanitizeFact(200+字)  = "
                + promptSanitizer.sanitizeFact("用户".repeat(150)));

        System.out.println("\n" + "=".repeat(88));
        System.out.println("STEP 3 —— HabitExtractor 真实调用（qwen-turbo，temperature 0.1）");
        System.out.println("=".repeat(88));
        List<ExtractedHabit> habits = null;
        try {
            var extracted = habitExtractor.extract(DIALOGUE);
            System.out.println("  返回信封 = " + extracted);
            habits = (extracted == null) ? null : extracted.habits();
            System.out.println("  返回条数 = " + (habits == null ? "null" : habits.size()));
            if (habits != null) {
                for (int i = 0; i < habits.size(); i++) {
                    ExtractedHabit h = habits.get(i);
                    System.out.printf("  [%d] fact=%s%n", i, h == null ? "null" : h.fact());
                    System.out.printf("      ruleType=%s%n", h == null ? "null" : h.ruleType());
                    System.out.printf("      ruleType 解析 = %s%n",
                            h == null ? "-" : String.valueOf(SemanticRuleType.parse(h.ruleType())));
                    System.out.printf("      消毒后 = %s%n",
                            h == null ? "-" : String.valueOf(promptSanitizer.sanitizeFact(h.fact())));
                }
            }
        } catch (Exception e) {
            System.out.println("  !! extract() 抛异常: " + e.getClass().getName() + " : " + e.getMessage());
            e.printStackTrace(System.out);
        }

        System.out.println("\n" + "=".repeat(88));
        System.out.println("STEP 4 —— processAndPromote 落库（调用前基线）");
        System.out.println("=".repeat(88));
        printCandidate();
        printMemory();

        if (habits != null && !habits.isEmpty()) {
            try {
                semanticMemoryService.processAndPromote(USER_ID, habits);
                System.out.println("  processAndPromote 执行完毕");
            } catch (Exception e) {
                System.out.println("  !! processAndPromote 抛异常: " + e.getClass().getName()
                        + " : " + e.getMessage());
                e.printStackTrace(System.out);
            }
        } else {
            System.out.println("  「提取结果为空」，跳过晋升（这本身就是 0 候选行的直接原因）");
        }

        System.out.println("\n" + "=".repeat(88));
        System.out.println("STEP 5 —— 落库后结果");
        System.out.println("=".repeat(88));
        printCandidate();
        printMemory();
        System.out.println("=".repeat(88) + "\n");
    }

    /**
     * 三会话合并模拟：S1/S2/S3 措辞不同但经 LLM 标准化后是同一条 fact，
     * 因此等价于「同一 fact 经 3 个独立扫描窗口调用 processAndPromote」。
     * 期望：唯一候选行 hit_count=3、promoted=1，正式表 1 行，Qdrant 1 个点。
     */
    @Test
    void threeSessionDedupAndPromotion() {
        System.out.println("\n" + "=".repeat(88));
        System.out.println("三会话合并模拟：同一 fact 走 3 个独立窗口");
        System.out.println("=".repeat(88));

        List<List<ExtractedHabit>> windows = List.of(
                List.of(new ExtractedHabit("用户偏好使用 Vue3 进行前端开发", "PREFERENCE")),
                List.of(new ExtractedHabit("用户偏好使用 Vue3 进行前端开发", "PREFERENCE")),
                List.of(new ExtractedHabit("用户偏好使用 Vue3 进行前端开发", "PREFERENCE")));

        int i = 0;
        for (List<ExtractedHabit> window : windows) {
            i++;
            semanticMemoryService.processAndPromote(USER_ID, window);
            System.out.println("--- 第 " + i + " 个窗口调用后 ---");
            printCandidate();
            printMemory();
        }

        List<UserSemanticCandidate> rows = candidateMapper.selectList(null);
        int memoryRows = memoryMapper.selectList(null).size();
        boolean pass = rows.size() == 1
                && "3".equals(String.valueOf(rows.get(0).getHitCount()))
                && "1".equals(String.valueOf(rows.get(0).getPromoted()))
                && memoryRows == 1;
        System.out.println("\n  " + (pass
                ? "✅ PASS：唯一候选行 hit_count=3、promoted=1，正式表 1 行"
                : "❌ FAIL：候选行数=" + rows.size() + "，正式行数=" + memoryRows
                  + "（详见上方明细）"));
        System.out.println("=".repeat(88) + "\n");
    }

    private void printCandidate() {
        List<UserSemanticCandidate> rows = candidateMapper.selectList(null);
        System.out.println("  [user_semantic_candidate] 共 " + rows.size() + " 行");
        for (UserSemanticCandidate c : rows) {
            System.out.printf("    id=%s userId=%s hit=%s promoted=%s rule=%s hash=%s fact=%s%n",
                    c.getId(), c.getUserId(), c.getHitCount(), c.getPromoted(),
                    c.getRuleType(), c.getFactHash(), c.getFact());
        }
    }

    private void printMemory() {
        List<UserSemanticMemory> rows = memoryMapper.selectList(null);
        System.out.println("  [user_semantic_memory] 共 " + rows.size() + " 行（含逻辑删除）");
        for (UserSemanticMemory m : rows) {
            System.out.printf("    id=%s userId=%s rule=%s fact=%s%n",
                    m.getId(), m.getUserId(), m.getRuleType(), m.getFact());
        }
    }
}
