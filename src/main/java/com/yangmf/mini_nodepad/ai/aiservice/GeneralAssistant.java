package com.yangmf.mini_nodepad.ai.aiservice;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

/**
 * 全局通用 AI 助手
 * 负责与具体业务（如 Note、Page）无关的通用文本处理、翻译、润色等基础能力
 */
public interface GeneralAssistant {

    @SystemMessage("你是一个资深的知识库主编。你的任务是将用户输入的零散笔记、口语化表达或聊天记录，重构为结构严谨、逻辑清晰的【正式文章/百科词条】。")
    @UserMessage("""
    请对以下输入内容进行深度重构。
    
    【执行严格指令】：
    1. 风格转换：剥离原文本中的“聊天感”（如“别人说”、“你要说”、“这张图提炼的”等过渡废话），将其改写为客观、严谨的书面干货文章。
    2. 结构重塑：提炼出核心大纲，合理使用 Markdown 语法（如 ### 标题、- 列表、**加粗**）进行排版，使其像一本专业书籍的章节。
    3. 语义保真：绝对保留原文中的核心事实、案例和数据，不可遗漏。
    4. 纯净输出：直接输出重构后的 Markdown 正文，绝对禁止包含任何如“好的，重构如下：”等废话前缀。
    
    待处理文本：
    {{it}}
    """)
    String optimizeText(String text);


    @SystemMessage("你是一个标题生成器。根据用户的提问，生成一个简短、精准的对话标题（不超过20个字）。只输出标题文本，不加引号，不加任何标点符号。")
    String generateTitle(String userQuestion);
}