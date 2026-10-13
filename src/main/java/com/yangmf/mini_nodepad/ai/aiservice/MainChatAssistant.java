package com.yangmf.mini_nodepad.ai.aiservice;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import dev.langchain4j.service.TokenStream;

public interface MainChatAssistant {

    /**
     * RAG 主链路（两段流式）。
     *
     * <p>2026-10-12 新增 {@code passDirective}：本轮是「两段式回答」的哪一段、以及与上一段如何衔接。
     * 为什么不直接给模型设 maxTokens —— LangChain4j 的 AiServices 无法按调用覆盖模型参数，
     * 所以 Pass1 的"软预算"只能靠提示词约束（这正是"软"的含义：靠模型自觉收束，不是硬截断）。
     *
     * @param passDirective 阶段指令：含本段角色（开场 / 续写）、软预算、衔接纪律；
     *                      Pass2 时还会携带第一段已输出内容，供模型"接着上文写、不重复"
     */
    @SystemMessage(fromResource = "prompt/CoreChatPrompt.txt")
    TokenStream chat(
            @MemoryId String memoryId,
            @V("roleInstruction") String roleInstruction,
            @V("lengthConstraint") String lengthConstraint,
            @V("context") String context,
            @V("historySummary") String historySummary,
            @V("regenerateInstruction") String regenerateInstruction,
            @V("userGlobalMemory") String userGlobalMemory,
            @V("passDirective") String passDirective,
            @UserMessage String question
    );

    @SystemMessage("""
    你是 Mini-NotebookLM，一个智能知识库助手。
    你可以和用户进行友好的日常交流，保持回答简短精炼。
    如果用户问起你的功能，请告诉用户：你能够基于用户上传的文档进行智能问答，支持多轮对话、知识检索、引用溯源。
    如果用户的话题涉及专业知识或事实性内容，请基于你的通识自然、简短地回应，像朋友讨论一样。
    绝不输出任何写死的引导话术；若话题明显与用户的笔记相关，可以顺带自然地提一句：
    选中相关笔记后你能结合知识库给出更精准、带引用的回答。

    【历史对话背景】
    {{historySummary}}

    【最近对话原文】
    {{recentContext}}

    {{regenerateInstruction}}

    {{userGlobalMemory}}
    """)
    TokenStream chat(
            @MemoryId String memoryId,
            @V("historySummary") String historySummary,
            @V("recentContext") String recentContext,
            @V("regenerateInstruction") String regenerateInstruction,
            @V("userGlobalMemory") String userGlobalMemory,
            @UserMessage String message
    );
}