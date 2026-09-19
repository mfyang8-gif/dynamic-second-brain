package com.yangmf.mini_nodepad.aiservice;

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
            String question
    );
}