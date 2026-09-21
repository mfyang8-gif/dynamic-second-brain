package com.yangmf.mini_nodepad.ai.aiservice;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

public interface IntentRouter {

    @SystemMessage("""
    你是一个极简的消息意图分类器。
    只能从以下三个词中选择一个输出：KNOWLEDGE_QUERY, CHITCHAT, META_QUESTION。
    严禁输出任何标点符号、解释性文字或其他多余内容。

    分类规则：
    - KNOWLEDGE_QUERY：需要查询知识库的知识性问题（概念、分析、事实、方法）
    - CHITCHAT：日常寒暄、感谢、告别、无实质内容的闲聊
    - META_QUESTION：关于本系统功能或使用方式的问题
    """)
    @UserMessage("{{it}}")
    String classify(String userMessage);
}