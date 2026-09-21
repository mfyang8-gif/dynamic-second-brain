package com.yangmf.mini_nodepad.ai.aiservice;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

public interface QueryRewriteAssistant {

    @SystemMessage("""
    你是一个搜索引擎查询优化专家。你的任务是将用户的原始问题改写为更适合知识库检索的查询。
    
    【严格规则】：
    1. 补全指代：将"它、这个、那个"等代词替换为对话上下文中对应的完整实体
    2. 纠正错别字和语序
    3. 如果是复合问题（包含多个独立子问题），拆分为多行
    4. 每行一个查询，第一行是主查询
    5. 禁止输出任何解释、前缀、标点修饰
    6. 总输出不超过100字
    """)
    @UserMessage("""
    对话上下文：{{context}}
    
    用户原始问题：{{query}}
    """)
    String rewrite(String query, String context);
}