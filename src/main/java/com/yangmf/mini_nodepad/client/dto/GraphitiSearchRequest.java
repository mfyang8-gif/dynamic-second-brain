package com.yangmf.mini_nodepad.client.dto;

import java.util.List;

/**
 * {@code POST /search} 请求体（服务端 {@code SearchQuery}）。
 *
 * <p>⚠️ 陷阱 5（本 Phase 最危险的一条）：{@code group_ids} 在服务端是
 * {@code list[str] | None = None} —— <b>可选且默认无过滤</b>。
 * 漏传、传 null，或把字段名写成单数 {@code group_id}（字段被静默丢弃），
 * 三种情况都会变成<b>跨租户全局检索，且 HTTP 200 零报错</b>。
 *
 * <p>且 {@code FactResult} 返回体里<b>没有 group_id</b>（陷阱 9）→ 客户端拿到结果后
 * 无法自证它属于谁。所以租户隔离的责任 100% 落在本字段上，
 * 构造期强制非空是唯一可靠的防线（P5b）。
 */
public record GraphitiSearchRequest(

        /** ★ 租户隔离键列表，构造期强制非空。 */
        List<String> groupIds,

        /** 检索语句（中文可直接放 body）。 */
        String query,

        /** {@code max_facts}：调用方传的应是「宽拉取后的条数」= limit × fetchFactor。 */
        Integer maxFacts) {

    public GraphitiSearchRequest {
        if (groupIds == null || groupIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "GraphitiSearchRequest.groupIds 不能为空：服务端对空 group_ids 不做任何过滤，"
                            + "会返回全部租户的数据且 HTTP 200（跨租户泄露）");
        }
        for (String groupId : groupIds) {
            if (groupId == null || groupId.isBlank()) {
                throw new IllegalArgumentException(
                        "GraphitiSearchRequest.groupIds 含空元素：空白 group_id 不构成有效隔离");
            }
        }
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("GraphitiSearchRequest.query 不能为空");
        }
        if (query.length() > MAX_QUERY_LENGTH) {
            throw new IllegalArgumentException(
                    "GraphitiSearchRequest.query 超长（" + query.length() + " > " + MAX_QUERY_LENGTH
                            + "）：检索语句不是载荷通道，超长输入应在进入检索前就被上游拦住");
        }
    }

    /**
     * 检索语句长度上限（输入防御）。
     *
     * <p>与 {@code guard.max-question-length}（2000）对齐：能走到召回这一步的 query
     * 本就应当是通过输入清洗的提问，超出这个量级说明调用链上游出了问题，
     * 此时快速失败比把超长文本透传到图谱服务更安全。
     */
    public static final int MAX_QUERY_LENGTH = 2000;
}
