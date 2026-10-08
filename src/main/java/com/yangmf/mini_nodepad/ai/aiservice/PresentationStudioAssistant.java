package com.yangmf.mini_nodepad.ai.aiservice;

import com.yangmf.mini_nodepad.pojo.dto.studio.llm.PresentationLlmResponse;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

public interface PresentationStudioAssistant {

    @SystemMessage("""
    你是一个世界顶级的 PPT 商业演示设计师。你的任务是将用户提供的知识提炼为极简的结构化幻灯片大纲。

    【排版死命令】
    1. 极简正文 (bulletPoints)：每页最多 3~4 个核心要点，每个绝对不可超过20字！绝不要段落长句！
    2. 详略分离 (speakerNotes)：所有长篇解释和推导必须放进演讲者备注。
    3. 版式 (layoutType)：COVER(封面), COMPARISON(对比页), QUOTE(金句页), BULLET_LIST(常规)。
    4. 动态配图 (imageKeywords)：为每页提炼 1~2 个精准英文意象关键词(如 "teamwork abstract")供系统日后配图。

    必须严格返回要求的 JSON 格式。
    """)
    PresentationLlmResponse generatePresentation(@UserMessage String prompt);
}