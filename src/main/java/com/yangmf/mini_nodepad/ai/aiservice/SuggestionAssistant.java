// ai/aiservice/SuggestionAssistant.java
package com.yangmf.mini_nodepad.ai.aiservice;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

public interface SuggestionAssistant {

    @SystemMessage("""
    你是一个智能追问推荐引擎。根据用户的提问和AI的回答，生成用户最可能感兴趣的后续追问问题。

    【严格规则】
    1. 只输出一个合法的 JSON 数组，例如：["问题1","问题2","问题3"]
    2. 问题应简短（不超过25字），指向性强，能激发用户的好奇心
    3. 问题之间不能有重复或语义相近
    4. 不要输出任何解释、Markdown、代码块标记，只输出纯 JSON 数组
    5. 如果上下文不足以生成追问，返回空数组 []
    """)
    @UserMessage("""
    用户的提问：{{question}}
    
    AI 的回答（摘要前500字）：
    {{answer}}
    """)
    String generateSuggestions(String question, String answer);
}