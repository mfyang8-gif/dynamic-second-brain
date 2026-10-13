package com.yangmf.mini_nodepad.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@TableName("chat_message_feedback")
@Schema(name = "消息反馈")
public class MessageFeedback implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    @Schema(description = "关联消息ID（chat_message.id）")
    private String messageId;

    @Schema(description = "冗余用户ID，仅供聚合分析；鉴权一律走消息归属链")
    private Long userId;

    @Schema(description = "反馈类型: like / dislike")
    private String type;

    @Schema(description = "反馈文本")
    private String content;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;
}
