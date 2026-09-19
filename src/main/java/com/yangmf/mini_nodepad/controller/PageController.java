package com.yangmf.mini_nodepad.controller;

import com.yangmf.mini_nodepad.exception.ValidationException;
import com.yangmf.mini_nodepad.pojo.dto.PageIngestDTO;
import com.yangmf.mini_nodepad.pojo.dto.PageQueryDTO;

import com.yangmf.mini_nodepad.pojo.vo.PageVO;
import com.yangmf.mini_nodepad.result.PageResult;
import com.yangmf.mini_nodepad.result.Result;
import com.yangmf.mini_nodepad.service.PageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/pages")
@RequiredArgsConstructor
@Tag(name = "知识页管理", description = "知识页的录入、查询、删除、回收站接口")
public class PageController {

    private final PageService pageService;


        //TODO：做根据内容生成标题
        // -插入时可选（AI生成标题Or保留提交标题）  -随时可选择重新生成

        //TODO：做根据内容生成摘要
        // -插入时可选（按原来自己简介OR Ai总结文章）  -随时可选择重新生成

        //TODO：入库qdrant时候选择的结构以及字段
    @PostMapping("/ingest")
    @Operation(summary = "录入知识页")
    public Result<Void> ingest(@Valid @RequestBody PageIngestDTO dto) {
        pageService.ingestPage(dto);
        return Result.success();
    }

    @GetMapping("/{id}")
    @Operation(summary = "获取知识页详情")
    public Result<PageVO> getById(@NotBlank(message = "知识页ID不能为空") @PathVariable String id) {
        return Result.success(pageService.getPageById(id));
    }

    @GetMapping("/book/{bookId}")
    @Operation(summary = "分页获取某本书下的知识页")
    public Result<PageResult<PageVO>> listByBook(@NotBlank(message = "书本ID不能为空") @PathVariable String bookId,
                                                 @Valid PageQueryDTO queryDTO) {
        return Result.success(pageService.listPagesByBookId(bookId, queryDTO));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除知识页（移入回收站）")
    public Result<Void> softDelete(@NotBlank(message = "知识页ID不能为空") @PathVariable String id) {
        pageService.softDeletePage(id);
        return Result.success();
    }

    @PostMapping("/batch/soft-delete")
    @Operation(summary = "批量删除知识页（移入回收站）")
    public Result<Void> batchSoftDelete(@NotEmpty(message = "请选择要删除的知识页") @RequestBody List<String> ids) {
        pageService.softDeletePages(ids);
        return Result.success();
    }

    @DeleteMapping("/{id}/permanent")
    @Operation(summary = "永久删除知识页（不可恢复）")
    public Result<Void> permanentDelete(@NotBlank(message = "知识页ID不能为空") @PathVariable String id) {
        pageService.permanentDeletePage(id);
        return Result.success();
    }

    @PostMapping("/batch/permanent-delete")
    @Operation(summary = "批量永久删除知识页（不可恢复）")
    public Result<Void> batchPermanentDelete(@NotEmpty(message = "请选择要删除的知识页") @RequestBody List<String> ids) {
        pageService.permanentDeletePages(ids);
        return Result.success();
    }

    @PutMapping("/{id}/restore")
    @Operation(summary = "从回收站恢复知识页")
    public Result<Void> restore(@NotBlank(message = "知识页ID不能为空") @PathVariable String id) {
        pageService.restorePage(id);
        return Result.success();
    }

    @PostMapping("/batch/restore")
    @Operation(summary = "批量从回收站恢复知识页")
    public Result<Void> batchRestore(@NotEmpty(message = "请选择要恢复的知识页") @RequestBody List<String> ids) {
        pageService.restorePages(ids);
        return Result.success();
    }

    @GetMapping("/recycle-bin")
    @Operation(summary = "分页查看回收站")
    public Result<PageResult<PageVO>> recycleBin(@Valid PageQueryDTO queryDTO) {
        return Result.success(pageService.listRecycleBin(queryDTO));
    }

    @DeleteMapping("/recycle-bin/empty")
    @Operation(summary = "清空回收站（不可恢复）")
    public Result<Void> emptyRecycleBin() {
        pageService.emptyRecycleBin();
        return Result.success();
    }

    @GetMapping("/ai/generate-title")
    @Operation(summary = "AI预览生成标题", description = "根据正文内容生成标题，不入库")
    public Result<String> generateTitlePreview(@RequestParam @NotBlank String content) {
        // 交给 Service 去做业务编排
        String title = pageService.generateTitlePreview(content);
        return Result.success(title);
    }



    @GetMapping("/ai/generate-summary")
    @Operation(summary = "AI预览生成简介", description = "根据正文内容生成简介，不入库")
    public Result<String> generateSummaryPreview(@RequestParam @NotBlank String content) {
        String summary = pageService.generateSummaryPreview(content);
        return Result.success(summary);
    }

    @PostMapping("/{id}/retry-ai")
    @Operation(summary = "重试 AI 处理", description = "重新触发标题/摘要生成和向量库写入，仅对失败或待处理的页面有效")
    public Result<Void> retryAiProcess(@NotBlank(message = "知识页ID不能为空") @PathVariable String id) {
        pageService.retryAiProcess(id);
        return Result.success();
    }


}