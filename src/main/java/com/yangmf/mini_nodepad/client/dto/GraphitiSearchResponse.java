package com.yangmf.mini_nodepad.client.dto;

import java.util.List;

/**
 * {@code POST /search} 的响应包装（服务端 {@code SearchResults}）。
 *
 * <p>服务端只回一个字段 {@code facts}；此处用独立 record 承接，
 * 而不是让调用方去处理裸数组 —— 数组为空与「字段缺失」在本项目里需要区分对待。
 */
public record GraphitiSearchResponse(

        /** 命中的事实列表，缺省为空列表。 */
        List<GraphitiFact> facts) {

    public GraphitiSearchResponse {
        if (facts == null) {
            facts = List.of();
        }
    }
}
