// ai/component/studio/FlashcardGenerator.java
package com.yangmf.mini_nodepad.ai.component.studio;

import com.yangmf.mini_nodepad.ai.aiservice.FlashcardStudioAssistant;
import com.yangmf.mini_nodepad.enums.studio.ArtifactType;
import com.yangmf.mini_nodepad.pojo.dto.studio.llm.FlashcardLlmResponse;
import com.yangmf.mini_nodepad.pojo.dto.studio.payload.FlashcardPayload;
import com.yangmf.mini_nodepad.pojo.dto.studio.payload.StudioProgressPayload;
import com.yangmf.mini_nodepad.pojo.dto.studio.request.FlashcardRequest;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Component
@RequiredArgsConstructor
public class FlashcardGenerator extends AbstractStudioGenerator<FlashcardRequest, FlashcardPayload> {

    private final FlashcardStudioAssistant flashcardAssistant;

    private static final ExecutorService VIRTUAL_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    private static final int BATCH_SIZE = 8;

    @Override
    public List<StudioProgressPayload> progressTimeline() {
        // 6 帧：概念 → 切分 → 提面 → 补例 → 分组 → 排序（2~3 秒一格）
        return List.of(
                new StudioProgressPayload("EXTRACT", "正在提取核心概念与术语...", 10),
                new StudioProgressPayload("SPLIT", "正在切分知识单元...", 26),
                new StudioProgressPayload("DISTILL", "正在提炼卡片正反面...", 44),
                new StudioProgressPayload("ENRICH", "正在补充例证与提示...", 62),
                new StudioProgressPayload("GROUP", "正在按类别分组卡片...", 80),
                new StudioProgressPayload("ORDER", "正在排定复习顺序...", 90)
        );
    }

    @Override
    public ArtifactType getType() {
        return ArtifactType.FLASHCARD;
    }

    @Override
    protected FlashcardPayload doGenerate(FlashcardRequest request, String context) {
        int totalCount = switch (request.getCountLevel()) {
            case FEW -> 8;
            case STANDARD -> 15;
            case MANY -> 25;
        };

        if (totalCount <= BATCH_SIZE) {
            String prompt = buildPrompt(request, totalCount, context);
            FlashcardLlmResponse response = flashcardAssistant.generateFlashcards(prompt);
            return toPayload(response);
        }

        List<Integer> batches = splitBatches(totalCount);
        log.info("Flashcard Scatter-Gather | 总数={}, 拆分为 {} 个并发批次", totalCount, batches.size());

        List<CompletableFuture<FlashcardLlmResponse>> futures = batches.stream()
                .map(batchCount -> CompletableFuture.supplyAsync(() -> {
                    String prompt = buildPrompt(request, batchCount, context);
                    return flashcardAssistant.generateFlashcards(prompt);
                }, VIRTUAL_EXECUTOR))
                .toList();

        List<FlashcardLlmResponse.Card> allCards = new ArrayList<>();
        for (CompletableFuture<FlashcardLlmResponse> future : futures) {
            try {
                FlashcardLlmResponse partial = future.get();
                if (partial != null && partial.getCards() != null) {
                    allCards.addAll(partial.getCards());
                }
            } catch (Exception e) {
                log.error("Flashcard 子任务执行失败", e);
                throw new RuntimeException("抽认卡生成子任务失败: " + e.getMessage(), e);
            }
        }

        log.info("Flashcard Scatter-Gather 完成 | 预期 {} 张，实际 {} 张", totalCount, allCards.size());

        FlashcardPayload result = new FlashcardPayload();
        result.setCards(allCards);
        return result;
    }

    private FlashcardPayload toPayload(FlashcardLlmResponse response) {
        FlashcardPayload payload = new FlashcardPayload();
        if (response != null && response.getCards() != null) {
            payload.setCards(response.getCards());
        }
        return payload;
    }

    private List<Integer> splitBatches(int totalCount) {
        List<Integer> batches = new ArrayList<>();
        int batchCount = (int) Math.ceil((double) totalCount / BATCH_SIZE);
        int perBatch = totalCount / batchCount;
        int remainder = totalCount % batchCount;

        for (int i = 0; i < batchCount; i++) {
            batches.add(perBatch + (i < remainder ? 1 : 0));
        }
        return batches;
    }

    private String buildPrompt(FlashcardRequest request, int count, String context) {
        return String.format("""
        【参数】
        - 卡片数量：请恰好生成 %d 张卡片，不多不少
        - 难度级别：%s
        - 提炼方向：%s
        - 语言：%s
        
        【知识库文档内容】
        %s
        
        请严格基于以上文档内容生成抽认卡，禁止编造文档中未提及的知识。
        """, count, request.getDifficulty(), request.getCardFocus(), request.getLanguage(), context);
    }
}
