package com.yangmf.mini_nodepad;

import com.yangmf.mini_nodepad.ai.rag.PageRagRetrievalService;
import com.yangmf.mini_nodepad.ai.rag.PageRagRetrievalService.RagChunk;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

@SpringBootTest
class RagScoreDiagnosticTest {

    @Autowired
    private PageRagRetrievalService ragService;

    // 请替换为你实际存入这4篇文章的 bookId
    private static final String BOOK_ID = "7eb8ee4edc4a496eaa966d59d97596d8";

    @Test
    void diagnoseScoreDistribution() {
        List<String> testQueries = List.of(
                // =======================================================
                // A 组：精准匹配（预期结果：高分命中，BM25 和 Dense 都会得分很高）
                // =======================================================
                "羊群效应的核心驱动力是什么",
                "乌合之众中群体的心理特征有哪些",
                "如何应对别人的质疑而不陷入自证清白的陷阱",
                "晏子二桃杀三士的核心逻辑是什么",
                "接话三原则中的疫情绪不接事是什么意思",

                // =======================================================
                // B 组：语义相关（预期结果：中高分命中，主要靠 Dense 向量语义命中）
                // =======================================================
                "为什么大家都去排队买网红奶茶，连觉得不好喝的人也会跟着买", // 对应：羊群效应-规范压力
                "在职场开会被领导当众批评工作不用心，该怎么高情商回复", // 对应：应对质疑-职场PUA拆解重建
                "一帮人聚在一起的时候，为什么会变得没有理智、容易冲动", // 对应：乌合之众-智力下降与情绪传染
                "历史上有哪些借别人的手除掉政敌的计谋", // 对应：博弈与权谋-借刀杀人/驱虎吞狼
                "别人跟我抱怨工作累受委屈，我该怎么接话才能让他觉得我懂他", // 对应：接话原则-疫情绪，照顾心情

                // =======================================================
                // C 组：边缘相关（预期结果：低分命中或不命中，包含原文少量关键词但意图不同）
                // =======================================================
                "股票投资怎么分析公司的基本面和市盈率估值", // 干扰点：羊群效应提到了"股市追涨杀跌"和"估值"
                "有没有推荐好看的电影，类似冰与火之歌或者教父那种风格的", // 干扰点：权谋案例提到了这两部影视剧作隐喻
                "如何提高自己的逻辑推理能力和数学成绩", // 干扰点：乌合之众提到了"推理"，沟通术提到了"讨厌数学课"
                "怎么用微信或者电话跟前任复合", // 干扰点：沟通术提到了"关于前任的猜忌"
                "牛奶和红茶怎么按照比例做奶茶最好喝", // 干扰点：羊群效应提到了"奶茶店"

                // =======================================================
                // D 组：完全无关（预期结果：不命中，用来测试系统的底线阈值）
                // =======================================================
                "今天北京天气怎么样，需要带伞吗",
                "红烧肉的正宗做法和配料表",
                "Java Spring Boot 中如何配置 Redis 缓存",
                "2026年世界杯在哪个国家举行",
                "感冒发烧流鼻涕吃什么药好得快"
        );

        for (String query : testQueries) {
            System.out.println("\n" + "=".repeat(80));
            System.out.printf("📝 Query: %s%n", query);
            System.out.println("=".repeat(80));

            List<RagChunk> chunks = ragService.retrieveChunks(query, BOOK_ID);

            if (chunks.isEmpty()) {
                System.out.println("  无结果 (Empty Result)");
                continue;
            }

            for (RagChunk chunk : chunks) {
                String textPreview = chunk.getText() != null
                        ? chunk.getText().replace("\n", " ").substring(0, Math.min(60, chunk.getText().length()))
                        : "null";
                System.out.printf("  [%d] score=%.5f  page=%s  text=%s...%n",
                        chunk.getDisplayIndex(), // 如果没有这个方法，可以换成循环的 index
                        chunk.getScore(),
                        chunk.getTitle(),
                        textPreview);
            }

            float maxScore = (float) chunks.stream().mapToDouble(RagChunk::getScore).max().orElse(0);
            float minScore = (float) chunks.stream().mapToDouble(RagChunk::getScore).min().orElse(0);
            float avgScore = (float) chunks.stream().mapToDouble(RagChunk::getScore).average().orElse(0);
            System.out.printf("  📊 统计 -> max: %.5f | min: %.5f | avg: %.5f | count: %d%n",
                    maxScore, minScore, avgScore, chunks.size());
        }
    }
}