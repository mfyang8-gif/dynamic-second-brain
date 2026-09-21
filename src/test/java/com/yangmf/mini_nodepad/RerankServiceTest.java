package com.yangmf.mini_nodepad;

import com.yangmf.mini_nodepad.ai.rag.PageRagRetrievalService.RagChunk;
import com.yangmf.mini_nodepad.ai.component.RerankService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Slf4j
class RerankServiceTest {

    @Autowired
    private RerankService rerankService;

    @Test
    void testRerankWithRealScoringModel() {
        String query = "文本排序模型在搜索引擎中的应用原理";

        List<RagChunk> candidates = new ArrayList<>();

        candidates.add(RagChunk.builder()
                .displayIndex(1)
                .text("文本排序模型（Text Ranking Model）广泛用于搜索引擎中，通过对候选文档与查询语句的语义相关性进行深度打分，将最相关的结果排在前面。常见的架构包括双塔模型、交叉注意力模型等，它们能够有效捕捉查询与文档之间的语义匹配关系。")
                .pageId("page-001")
                .bookId("book-001")
                .title("文本排序模型原理与应用")
                .summary("介绍排序模型在搜索中的核心作用")
                .chunkIndex(0)
                .embeddingId("emb-001")
                .score(0.85f)
                .build());

        candidates.add(RagChunk.builder()
                .displayIndex(2)
                .text("Rerank 重排序是信息检索领域的关键技术。传统的 BM25 算法基于词频统计，而现代的重排序模型如 BERT-based reranker 能够理解上下文语义，显著提升搜索结果的 Precision@K。阿里云的 gte-rerank 和 qwen3.7-text-rerank 都属于此类模型。")
                .pageId("page-002")
                .bookId("book-001")
                .title("Rerank 重排序技术详解")
                .summary("对比传统BM25与深度学习排序模型")
                .chunkIndex(1)
                .embeddingId("emb-002")
                .score(0.78f)
                .build());

        candidates.add(RagChunk.builder()
                .displayIndex(3)
                .text("红烧肉是一道经典的中国传统菜肴，主要原料为五花肉，配以冰糖、酱油、料酒等调料慢炖而成。做法讲究火候控制，需要先用小火煸炒出油脂，再加入调料和水慢炖约一小时。")
                .pageId("page-003")
                .bookId("book-001")
                .title("红烧肉做法")
                .summary("红烧肉的详细烹饪步骤")
                .chunkIndex(2)
                .embeddingId("emb-003")
                .score(0.12f)
                .build());

        candidates.add(RagChunk.builder()
                .displayIndex(4)
                .text("Python 中的 asyncio 库提供了编写异步代码的能力，通过 async/await 语法可以高效地处理 I/O 密集型任务。与多线程不同，asyncio 采用事件循环机制，避免了线程切换的开销。")
                .pageId("page-004")
                .bookId("book-001")
                .title("Python 异步编程入门")
                .summary("asyncio 事件循环与协程基础")
                .chunkIndex(3)
                .embeddingId("emb-004")
                .score(0.15f)
                .build());

        candidates.add(RagChunk.builder()
                .displayIndex(5)
                .text("2024年巴黎奥运会于7月26日至8月11日举行，共有来自200多个国家和地区的运动员参加32个大项的比赛。中国代表团在本届奥运会上获得了40枚金牌，创造了境外参赛的最佳成绩。")
                .pageId("page-005")
                .bookId("book-001")
                .title("巴黎奥运会回顾")
                .summary("2024巴黎奥运会赛况与奖牌榜")
                .chunkIndex(4)
                .embeddingId("emb-005")
                .score(0.08f)
                .build());

        candidates.add(RagChunk.builder()
                .displayIndex(6)
                .text("Spring Boot 的自动配置机制通过 @ConditionalOnClass 和 @ConditionalOnProperty 等注解实现条件化 Bean 注册。开发者可以通过 spring.factories 或 AutoConfiguration.imports 文件注册自定义自动配置类。")
                .pageId("page-006")
                .bookId("book-001")
                .title("Spring Boot 自动配置原理")
                .summary("深入理解条件注解与自动装配")
                .chunkIndex(5)
                .embeddingId("emb-006")
                .score(0.18f)
                .build());

        candidates.add(RagChunk.builder()
                .displayIndex(7)
                .text("冥想和正念练习已被多项临床研究证实能有效降低焦虑水平。每天坚持10-15分钟的专注呼吸练习，可以帮助调节自主神经系统，减少皮质醇分泌，从而改善整体的心理健康状态。")
                .pageId("page-007")
                .bookId("book-001")
                .title("冥想与心理健康")
                .summary("正念冥想对焦虑的缓解作用")
                .chunkIndex(6)
                .embeddingId("emb-007")
                .score(0.10f)
                .build());

        candidates.add(RagChunk.builder()
                .displayIndex(8)
                .text("量子计算利用量子比特的叠加和纠缠特性，在特定计算任务上实现指数级加速。Google 的 Sycamore 处理器在2019年首次实现了量子优越性，完成了一个经典计算机需要1万年才能完成的计算任务。")
                .pageId("page-008")
                .bookId("book-001")
                .title("量子计算前沿进展")
                .summary("量子优越性与量子比特技术")
                .chunkIndex(7)
                .embeddingId("emb-008")
                .score(0.06f)
                .build());

        System.out.println("\n" + "=".repeat(90));
        System.out.println("📋 Rerank 前 — 原始候选列表（按 RRF 分数排序）");
        System.out.println("=".repeat(90));
        System.out.printf("%-4s %-30s %-8s %-10s %s%n", "#", "标题", "RRF分数", "Rerank分数", "文本预览");
        System.out.println("-".repeat(90));
        for (RagChunk c : candidates) {
            String preview = c.getText().replace("\n", " ").substring(0, Math.min(50, c.getText().length()));
            System.out.printf("%-4d %-30s %-8.4f %-10s %s...%n",
                    c.getDisplayIndex(), c.getTitle(), c.getScore(), "—", preview);
        }

        List<RagChunk> reranked = rerankService.rerank(query, candidates);

        System.out.println("\n" + "=".repeat(90));
        System.out.println("✅ Rerank 后 — 精排结果（按 qwen3.7-text-rerank 分数排序，topN=5）");
        System.out.println("=".repeat(90));
        System.out.printf("%-4s %-30s %-8s %-10s %s%n", "#", "标题", "RRF分数", "Rerank分数", "文本预览");
        System.out.println("-".repeat(90));
        for (RagChunk c : reranked) {
            String preview = c.getText().replace("\n", " ").substring(0, Math.min(50, c.getText().length()));
            System.out.printf("%-4d %-30s %-8.4f %-10.6f %s...%n",
                    c.getDisplayIndex(), c.getTitle(), c.getScore(),
                    c.getRerankScore() != null ? c.getRerankScore() : 0.0f, preview);
        }

        assertTrue(reranked.size() <= 5,
                "返回结果数量应 <= topN(5)，实际: " + reranked.size());

        assertNotNull(reranked.get(0).getRerankScore(),
                "排名第一的 RagChunk 必须有 rerankScore");

        float topScore = reranked.get(0).getRerankScore();
        for (int i = 1; i < reranked.size(); i++) {
            Float currentScore = reranked.get(i).getRerankScore();
            assertNotNull(currentScore, "第 " + (i + 1) + " 个结果的 rerankScore 不应为 null");
            assertTrue(topScore >= currentScore,
                    "排名第一的 rerankScore(" + topScore + ") 应 >= 第 " + (i + 1) + " 名的 rerankScore(" + currentScore + ")");
        }

        for (RagChunk chunk : reranked) {
            assertTrue(chunk.getScore() > 0,
                    "原始 RRF score 应被保留且 > 0，实际: " + chunk.getScore() + " | title=" + chunk.getTitle());
            assertNotNull(chunk.getRerankScore(),
                    "rerankScore 应被赋上阿里云返回的浮点值 | title=" + chunk.getTitle());
            assertTrue(chunk.getRerankScore() >= 0.0f && chunk.getRerankScore() <= 1.0f,
                    "rerankScore 应在 [0, 1] 范围内，实际: " + chunk.getRerankScore() + " | title=" + chunk.getTitle());
        }

        RagChunk topChunk = reranked.get(0);
        boolean topIsRelevant = topChunk.getTitle().contains("排序模型")
                || topChunk.getTitle().contains("Rerank");
        assertTrue(topIsRelevant,
                "排名第一的应是高相关文档（排序模型/Rerank），实际: " + topChunk.getTitle());

        System.out.println("\n" + "=".repeat(90));
        System.out.println("📊 验证汇总");
        System.out.println("=".repeat(90));
        System.out.println("  ✅ 结果数量: " + reranked.size() + " (topN=5 截断生效)");
        System.out.println("  ✅ 排名第一: " + topChunk.getTitle() + " | rerankScore=" + topChunk.getRerankScore());
        System.out.println("  ✅ 原始 score 全部保留，rerankScore 独立赋值");
        System.out.println("  ✅ 所有断言通过");
    }

    @Test
    void testRerankSkipsWhenBelowThreshold() {
        String query = "任意查询";

        List<RagChunk> smallList = List.of(
                RagChunk.builder()
                        .displayIndex(1)
                        .text("短文本A")
                        .pageId("p1").bookId("b1").title("A").score(0.9f)
                        .build(),
                RagChunk.builder()
                        .displayIndex(2)
                        .text("短文本B")
                        .pageId("p2").bookId("b1").title("B").score(0.8f)
                        .build()
        );

        List<RagChunk> result = rerankService.rerank(query, smallList);

        assertSame(smallList, result,
                "候选数量 <= topN 时应直接返回原列表，不触发 Rerank API 调用");
        assertNull(result.get(0).getRerankScore(),
                "未触发 Rerank 时 rerankScore 应为 null");

        System.out.println("✅ 短路测试通过：候选数(2) <= topN(5)，直接返回原列表，未发起网络请求");
    }
}