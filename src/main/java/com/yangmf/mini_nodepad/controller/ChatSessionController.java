package com.yangmf.mini_nodepad.controller;

import com.yangmf.mini_nodepad.pojo.dto.ChatMessageQueryDTO;
import com.yangmf.mini_nodepad.pojo.dto.RegenerateDTO;
import com.yangmf.mini_nodepad.pojo.dto.SessionCreateDTO;
import com.yangmf.mini_nodepad.pojo.vo.ChatMessageVO;
import com.yangmf.mini_nodepad.pojo.vo.ChatSessionVO;
import com.yangmf.mini_nodepad.pojo.vo.RegenerateVO;
import com.yangmf.mini_nodepad.result.PageResult;
import com.yangmf.mini_nodepad.result.Result;
import com.yangmf.mini_nodepad.service.ChatSessionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Validated
@Tag(name = "对话会话管理", description = "多开页会话的创建、查询、删除接口")
public class ChatSessionController {

    private final ChatSessionService chatSessionService;

    @PostMapping("/sessions")
    @Operation(summary = "创建对话会话")
    public Result<String> create(@Valid @RequestBody SessionCreateDTO dto) {
        return Result.success(chatSessionService.createSession(dto));
    }

    @GetMapping("/books/{bookId}/sessions")
    @Operation(summary = "获取知识库下的所有会话列表")
    public Result<List<ChatSessionVO>> listByBook(
            @NotBlank(message = "知识库ID不能为空") @PathVariable String bookId) {
        return Result.success(chatSessionService.listSessionsByBookId(bookId));
    }

    @GetMapping("/sessions/{sessionId}")
    @Operation(summary = "获取会话详情")
    public Result<ChatSessionVO> getById(
            @NotBlank(message = "会话ID不能为空") @PathVariable String sessionId) {
        return Result.success(chatSessionService.getSessionById(sessionId));
    }

    @GetMapping("/sessions/{sessionId}/messages")
    @Operation(summary = "分页查询会话历史消息",
            description = "按时间正序返回。鉴权 ID 取自登录上下文，不信任任何前端传入的 userId；"
                    + "强制分页（pageSize ≤ 100）；返回 VO，已屏蔽内部字段。")
    public Result<PageResult<ChatMessageVO>> listMessages(
            @NotBlank(message = "会话ID不能为空") @PathVariable String sessionId,
            @Valid ChatMessageQueryDTO dto) {
        return Result.success(chatSessionService.listMessages(sessionId, dto));
    }

    @DeleteMapping("/sessions/{sessionId}")
    @Operation(summary = "删除会话")
    public Result<Void> delete(
            @NotBlank(message = "会话ID不能为空") @PathVariable String sessionId) {
        chatSessionService.deleteSession(sessionId);
        return Result.success();
    }

    @PostMapping("/sessions/{sessionId}/messages/{messageId}/regenerate")
    @Operation(summary = "重新生成",
            description = "软删除截断目标 Assistant 消息及其后所有消息，同步重建 ChatMemory；"
                    + "修正指令以一次性 Token 暂存 Redis（5 分钟），由前端在随后的 /chat/stream 请求中携带；"
                    + "本接口不吐 SSE 流，截断完成后直接返回。")
    public Result<RegenerateVO> regenerate(
            @NotBlank(message = "会话ID不能为空") @PathVariable String sessionId,
            @NotBlank(message = "消息ID不能为空") @PathVariable String messageId,
            @Valid @RequestBody RegenerateDTO dto) {
        return Result.success(chatSessionService.regenerate(sessionId, messageId, dto));
    }
}