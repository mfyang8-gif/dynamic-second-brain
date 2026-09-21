package com.yangmf.mini_nodepad.ai.aiservice;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

public interface ContextSummarizer {

    @SystemMessage("你是一个对话上下文压缩引擎。将旧摘要与新对话合并，输出一份更新后的完整摘要。")
    @UserMessage("""
    【旧摘要】：
    {{oldSummary}}
    
    【新增对话】：
    {{newMessages}}
    
    【输出规范】：
    1. 合并为一份完整摘要，不是追加，而是重新组织
    2. 保留所有关键事实、用户偏好、已确认结论、待解决问题
    3. 剔除已被后续对话推翻或过时的信息
    4. 使用 Markdown 列表格式，高度凝练，控制在 300 字以内
    5. 直接输出摘要内容，不加任何前缀
    """)
    String summarizeIncremental(String oldSummary, String newMessages);
}