package com.yangmf.mini_nodepad.ai.config;


import com.yangmf.mini_nodepad.ai.aiservice.*;

import dev.langchain4j.community.model.dashscope.QwenChatModel;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


@Configuration
public class AssistantConfig {


    private final OpenAiChatModel doubaoModel;


    private final QwenChatModel qwenChatModel;


    private final OpenAiStreamingChatModel streamingModel;

    private final ChatMemoryProvider chatMemoryProvider;



    public AssistantConfig(OpenAiChatModel doubaoModel, QwenChatModel qwenChatModel, OpenAiStreamingChatModel streamingModel, @Qualifier("chatMemoryProvider")ChatMemoryProvider chatMemoryProvider) {
        this.doubaoModel = doubaoModel;
        this.qwenChatModel = qwenChatModel;
        this.streamingModel = streamingModel;
        this.chatMemoryProvider = chatMemoryProvider;
    }

    @Bean
    public PageAssistant pageAssistant() {
        return AiServices.builder(PageAssistant.class)
                .chatModel(qwenChatModel)
                .build();
    }

    @Bean
    public GeneralAssistant generalAssistant() {
        return AiServices.builder(GeneralAssistant.class)
                .chatModel(qwenChatModel)
                .build();
    }

    /**
     * 主对话助手（流式）。
     *
     * <p>2026-10-12 用户裁决：<b>摘除 mcpToolProvider</b>。
     * 联网能力改由「预检索注入」（WebAugmentService）承担 —— 主模型回归纯流式生成，
     * 不再进入 Function Calling 思考循环：少一次工具往返、首字更快、输出完全确定性。
     * （MCP 工具链并未废弃：WebSearchService 仍通过 McpClient 直连 executeTool，
     * 只是不再挂在模型的 toolProvider 上。）
     *
     * <p>2026-10-12 二次裁决：<b>恒并行双路 + 两段流式</b>取代「串行漏斗 + 判卷器」——
     * 原 RelevanceJudge / RagRelevanceJudgeService 已彻底删除（不留历史包袱），
     * 本地与全网两路来源由主模型在提示词约束下自行裁决。
     */
    @Bean
    public MainChatAssistant mainChatAssistant() {
        return AiServices.builder(MainChatAssistant.class)
                .streamingChatModel(streamingModel)
                .chatMemoryProvider(chatMemoryProvider)
                .build();
    }

    @Bean
    public QueryRewriteAssistant queryAssistant() {
        return AiServices.builder(QueryRewriteAssistant.class)
                .chatModel(qwenChatModel)
                .build();
    }


    /** 工件两阶段命名助手：DRAFT 暂定名（并行）/ FINAL 精修名（落库前） */
    @Bean
    public ArtifactTitleAssistant artifactTitleAssistant() {
        return AiServices.builder(ArtifactTitleAssistant.class)
                .chatModel(qwenChatModel)
                .build();
    }

    @Bean
    public IntentRouter intentRouter() {
        return AiServices.builder(IntentRouter.class)
                .chatModel(qwenChatModel)
                .build();
    }

    /**
     * 习惯提取器专用模型实例：temperature 0.1 强化 JSON 结构化输出服从性（裁决 5）。
     * 不复用共享 qwenChatModel（温度 0.7 且后期可单独切 qwen-plus）；
     * API key 直接读 starter 自身已解析的配置项，缺失时降级共享 Bean 保启动。
     */
    @Bean
    public HabitExtractor habitExtractor(
            @Value("${langchain4j.community.dashscope.chat-model.api-key:}") String dashscopeApiKey) {
        dev.langchain4j.model.chat.ChatModel extractorModel =
                (dashscopeApiKey == null || dashscopeApiKey.isBlank())
                        ? qwenChatModel
                        : QwenChatModel.builder()
                                .apiKey(dashscopeApiKey)
                                .modelName("qwen-turbo")
                                .temperature(0.1f)
                                .build();
        return AiServices.builder(HabitExtractor.class)
                .chatModel(extractorModel)
                .build();
    }

    /**
     * 习惯去重仲裁器（方案 B 第二级判定）：与提取器同源但**独立建实例**。
     * 温度默认 0.0（比提取器的 0.1 更确定）——判定任务是二值逻辑题，不需要任何发散；
     * model-name 可配（默认 qwen-turbo），后续若判定质量不足可单独切 qwen-plus 而不动提取器。
     * API key 同样读 starter 已解析的配置项，缺失时降级共享 Bean 保启动。
     */
    @Bean
    public HabitJudge habitJudge(
            @Value("${langchain4j.community.dashscope.chat-model.api-key:}") String dashscopeApiKey,
            @Value("${semantic.judge.model-name:qwen-turbo}") String judgeModelName,
            @Value("${semantic.judge.temperature:0.0}") float judgeTemperature) {
        dev.langchain4j.model.chat.ChatModel judgeModel =
                (dashscopeApiKey == null || dashscopeApiKey.isBlank())
                        ? qwenChatModel
                        : QwenChatModel.builder()
                                .apiKey(dashscopeApiKey)
                                .modelName(judgeModelName)
                                .temperature(judgeTemperature)
                                .build();
        return AiServices.builder(HabitJudge.class)
                .chatModel(judgeModel)
                .build();
    }

    @Bean
    public SuggestionAssistant suggestionAssistant() {
        return AiServices.builder(SuggestionAssistant.class)
                .chatModel(qwenChatModel)
                .build();
    }



    @Bean
    public QuizStudioAssistant quizStudioAssistant() {
        return AiServices.builder(QuizStudioAssistant.class)
                .chatModel(qwenChatModel)
                .build();
    }

    @Bean
    public FlashcardStudioAssistant flashcardStudioAssistant() {
        return AiServices.builder(FlashcardStudioAssistant.class)
                .chatModel(qwenChatModel)
                .build();
    }

    @Bean
    public MindmapStudioAssistant mindmapStudioAssistant() {
        return AiServices.builder(MindmapStudioAssistant.class)
                .chatModel(qwenChatModel)
                .build();
    }

    @Bean
    public ReportStudioAssistant reportStudioAssistant() {
        return AiServices.builder(ReportStudioAssistant.class)
                .chatModel(qwenChatModel)
                .build();
    }

    @Bean
    public PresentationStudioAssistant presentationStudioAssistant() {
        return AiServices.builder(PresentationStudioAssistant.class)
                .chatModel(qwenChatModel)
                .build();
    }


}
