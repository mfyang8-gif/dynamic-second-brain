package com.yangmf.mini_nodepad.controller;

import com.yangmf.mini_nodepad.pojo.dto.SessionCreateDTO;
import com.yangmf.mini_nodepad.pojo.vo.ChatSessionVO;
import com.yangmf.mini_nodepad.result.Result;
import com.yangmf.mini_nodepad.service.ChatSessionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
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

    @DeleteMapping("/sessions/{sessionId}")
    @Operation(summary = "删除会话")
    public Result<Void> delete(
            @NotBlank(message = "会话ID不能为空") @PathVariable String sessionId) {
        chatSessionService.deleteSession(sessionId);
        return Result.success();
    }
}