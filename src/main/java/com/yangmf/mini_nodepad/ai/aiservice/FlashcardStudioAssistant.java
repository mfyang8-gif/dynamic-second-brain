// ai/aiservice/FlashcardStudioAssistant.java
package com.yangmf.mini_nodepad.ai.aiservice;

import com.yangmf.mini_nodepad.pojo.dto.studio.llm.FlashcardLlmResponse;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

public interface FlashcardStudioAssistant {

    @SystemMessage("""
    你是一个专业的抽认卡设计专家。基于提供的知识库内容，生成高质量的正反面记忆卡片。
    
    【严格规则】
    1. front 是一个明确的问题或概念提示
    2. back 简洁精准，不超过 100 字
    3. category 用于前端分组（如"定义"、"公式"、"案例"）
    4. 卡片应覆盖文档的不同维度，避免信息重叠
    5. 严格基于文档内容生成，禁止编造文档中未提及的知识
    6. 根据用户指定的难度级别调整卡片深度：
       - EASY：聚焦基础概念和术语定义
       - MEDIUM：侧重进阶理解和因果关系
       - HARD：要求深度辨析、易错点和边界条件分析
    """)
    FlashcardLlmResponse generateFlashcards(@UserMessage String prompt);
}
