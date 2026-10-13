package com.yangmf.mini_nodepad.pojo.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@Schema(name = "重新生成结果")
public class RegenerateVO {

    @Schema(description = "一次性重新生成凭证（恒非空，内含修正指令与被否决的上一版负例）；前端必须在随后的 /chat/stream 请求体 regenerateToken 字段中携带，5分钟有效；普通提问不携带，防止误消费")
    private String instructionToken;

    @Schema(description = "本次截断的消息数量")
    private Integer truncatedCount;
}
