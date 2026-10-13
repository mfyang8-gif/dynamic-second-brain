package com.yangmf.mini_nodepad.pojo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(name = "消息反馈请求")
public class FeedbackDTO {

    @NotBlank(message = "反馈类型不能为空")
    @Pattern(regexp = "^(like|dislike|none)$", message = "反馈类型仅支持 like/dislike/none")
    @Schema(description = "like-点赞 dislike-点踩 none-取消")
    private String type;

    @Size(max = 500, message = "反馈用语不能超过500字")
    @Schema(description = "可选反馈文本")
    private String content;
}
