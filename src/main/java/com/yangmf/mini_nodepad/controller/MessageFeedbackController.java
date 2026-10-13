package com.yangmf.mini_nodepad.controller;

import com.yangmf.mini_nodepad.pojo.dto.FeedbackDTO;
import com.yangmf.mini_nodepad.pojo.vo.FeedbackVO;
import com.yangmf.mini_nodepad.result.Result;
import com.yangmf.mini_nodepad.service.MessageFeedbackService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/messages")
@RequiredArgsConstructor
@Tag(name = "消息反馈", description = "Assistant 回复的点赞/点踩，独立表存储")
public class MessageFeedbackController {

    private final MessageFeedbackService messageFeedbackService;

    @PostMapping("/{messageId}/feedback")
    @Operation(summary = "提交/更新/取消反馈",
            description = "幂等 Upsert；type=none 取消反馈；归属校验走 message→session→book 链路，越权/不存在统一 403")
    public Result<FeedbackVO> feedback(
            @NotBlank(message = "消息ID不能为空") @PathVariable String messageId,
            @Valid @RequestBody FeedbackDTO dto) {
        return Result.success(messageFeedbackService.submitFeedback(messageId, dto));
    }
}
