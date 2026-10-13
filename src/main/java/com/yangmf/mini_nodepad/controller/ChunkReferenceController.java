package com.yangmf.mini_nodepad.controller;

import com.yangmf.mini_nodepad.pojo.vo.ChunkContextVO;
import com.yangmf.mini_nodepad.result.Result;
import com.yangmf.mini_nodepad.service.ChunkReferenceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/knowledge")
@RequiredArgsConstructor
@Tag(name = "引用原文", description = "知识库域分块上下文查询，供前端引用卡片展开")
public class ChunkReferenceController {

    private final ChunkReferenceService chunkReferenceService;

    @GetMapping("/pages/{pageId}/chunks/{chunkIndex}/context")
    @Operation(summary = "查询引用分块上下文",
            description = "归属校验走 page → book 链路，越权/不存在统一 403；返回当前块全文 + 前后块 100 字预览")
    public Result<ChunkContextVO> getChunkContext(
            @NotBlank(message = "知识页ID不能为空") @PathVariable String pageId,
            @Min(value = 0, message = "分块序号不能为负数") @PathVariable Integer chunkIndex) {
        return Result.success(chunkReferenceService.getChunkContext(pageId, chunkIndex));
    }
}
