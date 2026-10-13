package com.yangmf.mini_nodepad.pojo.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@Schema(name = "反馈最终状态")
public class FeedbackVO {

    @Schema(description = "消息ID")
    private String messageId;

    @Schema(description = "当前生效状态: like / dislike / none")
    private String type;

    @Schema(description = "反馈文本，none 时为 null")
    private String content;
}
