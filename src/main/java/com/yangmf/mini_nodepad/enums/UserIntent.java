package com.yangmf.mini_nodepad.enums;

/**
 * LLM 意图路由中枢枚举（取代旧 IntentType，原 META_QUESTION 语义并入 GENERAL_CHAT）。
 * <p>
 * HYBRID 同时承担级联降级的安全兜底位：LLM 研判超时/解析异常时返回它，
 * 走 RAG 主链路保障主流程不挂。
 * <p>
 * 2026-10-12 新增 {@link #WEB_SEARCH}：把「强时效 / 外部事实」类问题从主链路里
 * 显式剥离出来（用户裁决的「双轨制」轨一）—— 这类问题本地知识库天然无解，
 * 先检索再兜底会白白浪费一次 Qdrant 往返与数秒延迟。
 */
public enum UserIntent {

    /** 明确需要查阅外部文档、知识库、文章 */
    KNOWLEDGE_SEARCH,

    /** 明确指向之前的长对话历史或上下文（如"刚才说的""上次写的"），无需查文档 */
    EPISODIC_MEMORY,

    /** 普通闲聊、写代码、翻译、关于本系统功能的咨询等，无需查库 */
    GENERAL_CHAT,

    /**
     * 强时效性 / 外部实时事实（今天天气、最新版本号、股价、新闻、赛事结果等），
     * 或用户明确要求联网搜索 —— 轨一：跳过本地检索，直接联网预检索后作答。
     */
    WEB_SEARCH,

    /** 模棱两可，或既需要查文档又需要结合对话历史；异常兜底态 */
    HYBRID
}
