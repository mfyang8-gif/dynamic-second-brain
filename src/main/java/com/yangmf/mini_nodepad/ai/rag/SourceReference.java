package com.yangmf.mini_nodepad.ai.rag;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 引用来源片段（统一溯源模型）。
 *
 * <p>原为 {@link PageRagRetrievalService} 的嵌套 record；2026-10-12 提升为顶层 ——
 * 它现在要被三层共同引用：SSE done 事件序列化、chat_message 实体落库
 * （{@code source_references} 列 + 专用 TypeHandler）、ChatMessageVO 历史回放。
 *
 * <p>2026-10-12 二次扩展（用户裁决的「引用溯源完全统一」）：
 * 外网搜索结果与本地知识库命中【共用同一结构】，只靠 {@link SourceType} 区分 ——
 * 大模型与前端都不需要认识两种来源类型，落库与 done 事件契约也不必分叉。
 * <ul>
 *   <li>{@code LOCAL_RAG} —— 本地笔记命中：{@code pageId} + {@code chunkIndex} 有效，{@code url} 为空</li>
 *   <li>{@code WEB_SEARCH} —— 外网命中：{@code url} 有效，{@code pageId/chunkIndex} 为空</li>
 * </ul>
 *
 * <p>兼容性：旧数据（落库时还没有 sourceType 这一列内容）反序列化时该字段为 null，
 * 由下面的紧凑构造器规范化成 {@code LOCAL_RAG} —— 历史消息零迁移成本。
 *
 * <p>序列化面（前后端契约，勿改字段名）：
 * <pre>
 *   LOCAL_RAG : {"index":1,"title":"...","pageId":"...","chunkIndex":0,"textSnippet":"...","sourceType":"LOCAL_RAG","url":null}
 *   WEB_SEARCH: {"index":1,"title":"...","pageId":null,"chunkIndex":null,"textSnippet":"...","sourceType":"WEB_SEARCH","url":"https://..."}
 * </pre>
 */
@Schema(name = "引用来源片段")
public record SourceReference(
        int index,
        String title,
        String pageId,
        Integer chunkIndex,
        String textSnippet,
        SourceType sourceType,
        String url
) {

    /** 来源类型：本地知识库命中 / 外网搜索命中 */
    public enum SourceType {
        LOCAL_RAG,
        WEB_SEARCH
    }

    /** 紧凑构造器：sourceType 缺省即视为本地命中（旧数据 + 调用方省略两种场景都靠它兜住） */
    public SourceReference {
        if (sourceType == null) {
            sourceType = SourceType.LOCAL_RAG;
        }
    }

    /** 旧签名（5 参）保留：本地命中调用方无需感知本次扩展 */
    public SourceReference(int index, String title, String pageId, Integer chunkIndex, String textSnippet) {
        this(index, title, pageId, chunkIndex, textSnippet, SourceType.LOCAL_RAG, null);
    }

    /** 本地知识库命中 */
    public static SourceReference local(int index, String title, String pageId,
                                        Integer chunkIndex, String textSnippet) {
        return new SourceReference(index, title, pageId, chunkIndex, textSnippet,
                SourceType.LOCAL_RAG, null);
    }

    /** 外网搜索命中：主键是 URL，没有 pageId / chunkIndex */
    public static SourceReference web(int index, String title, String url, String textSnippet) {
        return new SourceReference(index, title, null, null, textSnippet,
                SourceType.WEB_SEARCH, url);
    }
}
