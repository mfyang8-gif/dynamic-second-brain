package com.yangmf.mini_nodepad.aiservice;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

public interface ContextSummarizer {

    @SystemMessage("你是一个专业的对话上下文压缩引擎。你的任务是提炼对话核心，剔除冗余信息，以便在有限的内存中无缝延续后续的对话逻辑。")
    @UserMessage("""
    请对以下历史对话进行精准总结。你需要像一个专业的会议记录员一样，提取影响后续交互的关键信息。
    
    【核心要求】：
    1. 核心主题：概括探讨的主要话题、涉及的核心知识点或业务场景。
    2. 关键事实与结论：保留双方已经确认的核心事实、数据、代码逻辑或最终得出的结论。
    3. 用户偏好与指令：记录用户明确提出的个性化要求、约束条件或特定的输出偏好。
    4. 待解决状态：明确列出当前尚未解决的疑问、正在进行的任务或下一步计划。
    
    【输出规范】：
    1. 坚决剔除无效的客套话、语气词和无意义的过渡句。
    2. 使用层级清晰的 Markdown 列表格式输出。
    3. 语言必须高度凝练，总结长度严格控制在 200-500 字以内。
    
    对话历史：
    {{it}}
    """)
    String summarize(String conversation);
}