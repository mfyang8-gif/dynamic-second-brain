package com.yangmf.mini_nodepad.pojo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 会话历史消息查询参数。
 *
 * 安全红线：这里【不允许】出现 userId 字段 ——
 * 鉴权 ID 只能从 BaseContext（HTTP 线程上下文）取，
 * 信任前端传入的 userId 就是横向越权漏洞。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(name = "会话历史消息查询参数")
public class ChatMessageQueryDTO extends PageQueryDTO {
}
