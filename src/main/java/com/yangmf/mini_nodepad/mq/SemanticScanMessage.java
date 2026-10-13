package com.yangmf.mini_nodepad.mq;

import java.io.Serializable;

/**
 * 语义扫描调度消息：消费线程无 HTTP 上下文，userId 必须随消息体显式传递（禁触 BaseContext）。
 *
 * @param msgId     Producer 生成的幂等键（MQ at-least-once 重复投递由消费端 Redis setnx 去重）
 * @param sessionId 待扫描会话
 * @param userId    消息签发时的用户（冗余存储防后续归属查询开销）
 */
public record SemanticScanMessage(String msgId, String sessionId, Long userId) implements Serializable {
}
