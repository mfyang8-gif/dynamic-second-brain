package com.yangmf.mini_nodepad.ai.component.studio;

import com.yangmf.mini_nodepad.ai.aiservice.QuizStudioAssistant;
import com.yangmf.mini_nodepad.enums.studio.ArtifactType;
import com.yangmf.mini_nodepad.enums.studio.QuestionType;
import com.yangmf.mini_nodepad.pojo.dto.studio.llm.QuizLlmResponse;
import com.yangmf.mini_nodepad.pojo.dto.studio.payload.QuizPayload;
import com.yangmf.mini_nodepad.pojo.dto.studio.payload.StudioProgressPayload;
import com.yangmf.mini_nodepad.pojo.dto.studio.request.QuizRequest;

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
public class QuizGenerator extends AbstractStudioGenerator<QuizRequest, QuizPayload> {

    private final QuizStudioAssistant quizAssistant;

    private static final ExecutorService VIRTUAL_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    @Override
    public List<StudioProgressPayload> progressTimeline() {
        return List.of(
                new StudioProgressPayload("EXTRACT", "正在检索知识库核心考点...", 15),
                new StudioProgressPayload("COMPOSE", "正在编写题干与选项...", 50),
                new StudioProgressPayload("EXPLAIN", "正在生成答案与详细解析...", 80),
                new StudioProgressPayload("BALANCE", "正在校验题型与难度分布...", 92)
        );
    }

    @Override
    public ArtifactType getType() {
        return ArtifactType.QUIZ;
    }

    @Override
    protected QuizPayload doGenerate(QuizRequest request, String context) {
        int totalCount = switch (request.getCountLevel()) {
            case FEW -> 5;
            case STANDARD -> 10;
            case MANY -> 15;
        };

        List<QuestionType> types = request.getQuestionTypes();

        if (types == null || types.isEmpty()) {
            String prompt = buildSinglePrompt(request, totalCount, null, context);
            QuizLlmResponse response = quizAssistant.generateQuiz(prompt);
            return toPayload(response);
        }

        List<Task> tasks = splitTasks(types, totalCount);
        log.info("Quiz Scatter-Gather | 拆分为 {} 个并发任务", tasks.size());

        List<CompletableFuture<QuizLlmResponse>> futures = tasks.stream()
                .map(task -> CompletableFuture.supplyAsync(() -> {
                    String prompt = buildSinglePrompt(request, task.count, task.type, context);
                    return quizAssistant.generateQuiz(prompt);
                }, VIRTUAL_EXECUTOR))
                .toList();

        List<QuizLlmResponse.Question> allQuestions = new ArrayList<>();
        for (CompletableFuture<QuizLlmResponse> future : futures) {
            try {
                QuizLlmResponse partial = future.get();
                if (partial != null && partial.getQuestions() != null) {
                    allQuestions.addAll(partial.getQuestions());
                }
            } catch (Exception e) {
                log.error("Quiz 子任务执行失败", e);
                throw new RuntimeException("测验生成子任务失败: " + e.getMessage(), e);
            }
        }

        log.info("Quiz Scatter-Gather 完成 | 预期 {} 道，实际 {} 道", totalCount, allQuestions.size());

        QuizPayload result = new QuizPayload();
        result.setQuestions(allQuestions);
        return result;
    }

    private QuizPayload toPayload(QuizLlmResponse response) {
        QuizPayload payload = new QuizPayload();
        if (response != null && response.getQuestions() != null) {
            payload.setQuestions(response.getQuestions());
        }
        return payload;
    }

    private List<Task> splitTasks(List<QuestionType> types, int totalCount) {
        List<Task> tasks = new ArrayList<>();
        int perType = totalCount / types.size();
        int remainder = totalCount % types.size();

        for (int i = 0; i < types.size(); i++) {
            int count = perType + (i < remainder ? 1 : 0);
            if (count > 0) {
                tasks.add(new Task(types.get(i), count));
            }
        }
        return tasks;
    }

    private String buildSinglePrompt(QuizRequest request, int count, QuestionType type, String context) {
        String typeInstruction;
        if (type == null) {
            typeInstruction = "题型由你自行合理搭配，建议以选择题为主，适当穿插填空和简答题。";
        } else {
            typeInstruction = String.format("全部生成 %s 题型，共 %d 道。", type, count);
        }

        return String.format("""
        【测验参数】
        - 题目总数：%d 道
        - 难度级别：%s
        - 侧重方向：%s
        - 语言：%s
        
        【题型分配】
        %s
        
        【知识库文档内容】
        %s
        
        请严格基于以上文档内容生成测验题目。每道题必须包含准确的答案和详细的解析。
        禁止编造文档中未涉及的知识点。
        """, count, request.getDifficulty(), request.getQuizFocus(),
                request.getLanguage(), typeInstruction, context);
    }

    private record Task(QuestionType type, int count) {}
}
