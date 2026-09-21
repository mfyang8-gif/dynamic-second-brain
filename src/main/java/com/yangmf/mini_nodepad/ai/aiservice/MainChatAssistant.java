package com.yangmf.mini_nodepad.ai.aiservice;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.V;
import dev.langchain4j.service.TokenStream;

public interface MainChatAssistant {

    @SystemMessage(fromResource = "prompt/CoreChatPrompt.txt")
    TokenStream chat(
            @MemoryId String memoryId,
            @V("roleInstruction") String roleInstruction,
            @V("lengthConstraint") String lengthConstraint,
            @V("context") String context,
            @V("historySummary") String historySummary,
            String question
    );

    @SystemMessage("""
    你是 Mini-NotebookLM，一个智能知识库助手。
    你可以和用户进行友好的日常交流，保持回答简短精炼。
    如果用户问起你的功能，请告诉用户：你能够基于用户上传的文档进行智能问答，支持多轮对话、知识检索、引用溯源。
    如果用户的问题涉及具体的专业知识或事实，请引导用户："您可以针对知识库中的具体内容向我提问，我会为您精准检索并回答。"
    
    【历史对话背景】
    {{historySummary}}
    """)
    TokenStream chat(
            @MemoryId String memoryId,
            @V("historySummary") String historySummary,
            String message
    );
}