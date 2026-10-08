// ai/aiservice/MindmapStudioAssistant.java
package com.yangmf.mini_nodepad.ai.aiservice;

import com.yangmf.mini_nodepad.pojo.dto.studio.llm.MindmapLlmResponse;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

public interface MindmapStudioAssistant {

    @SystemMessage("""
    你是一个知识架构师，擅长将复杂知识体系组织为层次分明的思维导图结构。
    
    【严格规则】
    1. root 的 children 递归嵌套，每层节点不超过 6 个
    2. 叶子节点 children 为空数组
    3. appliedStructure 说明实际采用的结构类型
    4. reasoning 简述为什么选择这种结构
    5. 导图应体现知识的整体脉络，而非碎片罗列
    """)
    MindmapLlmResponse generateMindmap(@UserMessage String prompt);
}