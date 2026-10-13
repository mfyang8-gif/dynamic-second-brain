package com.yangmf.mini_nodepad.client.dto;

import java.util.List;

/**
 * {@code POST /messages} 请求体（服务端 {@code AddMessagesRequest}）。
 *
 * <p>服务端只认两个字段：{@code group_id} 与 {@code messages}。序列化由 client 自建的
 * ObjectMapper 按 snake_case 下发（绝不共用全局 mapper，见 B0 的 impl）。
 *
 * <p>⚠️ 陷阱 1：{@code messages} 是<b>数组</b>，漏包一层即 422
 * （{@code {"loc":["body","messages"],"msg":"Field required"}}）。
 * 因此本类不对外暴露裸集合 —— 构造必须经过它，包装动作没有绕过的余地。
 */
public record GraphitiWriteRequest(

        /** 租户隔离键，唯一由 {@code GroupIdResolver} 生成，形如 {@code user_{userId}}。 */
        String groupId,

        /** 待写入消息。服务端对每条独立建 episode。 */
        List<GraphitiMessage> messages) {

    public GraphitiWriteRequest {
        if (groupId == null || groupId.isBlank()) {
            throw new IllegalArgumentException(
                    "GraphitiWriteRequest.groupId 不能为空：空 group_id 会把数据写进无主分区，事后无法按租户清理");
        }
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("GraphitiWriteRequest.messages 不能为空（服务端会 422）");
        }
    }

    /** 单条投递（设计上 N=1，见 §3.9）。 */
    public static GraphitiWriteRequest of(String groupId, GraphitiMessage message) {
        return new GraphitiWriteRequest(groupId, List.of(message));
    }
}
