// controller/StudioController.java
package com.yangmf.mini_nodepad.controller;

import com.yangmf.mini_nodepad.enums.studio.ArtifactType;
import com.yangmf.mini_nodepad.pojo.dto.studio.query.StudioHistoryQueryDTO;
import com.yangmf.mini_nodepad.pojo.dto.studio.request.FlashcardRequest;
import com.yangmf.mini_nodepad.pojo.dto.studio.request.MindmapRequest;
import com.yangmf.mini_nodepad.pojo.dto.studio.request.PresentationRequest;
import com.yangmf.mini_nodepad.pojo.dto.studio.request.QuizRequest;
import com.yangmf.mini_nodepad.pojo.dto.studio.request.ReportRequest;
import com.yangmf.mini_nodepad.pojo.entity.StudioArtifact;
import com.yangmf.mini_nodepad.pojo.vo.StudioArtifactDetailVO;
import com.yangmf.mini_nodepad.pojo.vo.StudioArtifactListVO;
import com.yangmf.mini_nodepad.result.PageResult;
import com.yangmf.mini_nodepad.result.Result;
import com.yangmf.mini_nodepad.service.StudioService;
import com.yangmf.mini_nodepad.service.impl.StudioServiceImpl;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/studio")
@RequiredArgsConstructor
@Tag(name = "Studio 衍生工具箱", description = "测验、抽认卡、思维导图、报告、PPT 的异步生成接口")
public class StudioController {

    private final StudioService studioServiceImpl;

    @PostMapping("/quiz")
    @Operation(summary = "生成测验", description = "异步生成测验题目，通过全局 SSE 通道推送结果")
    public Result<String> submitQuiz(@Valid @RequestBody QuizRequest request) {
        String artifactId = studioServiceImpl.submit(request, ArtifactType.QUIZ);
        return Result.success(artifactId);
    }

    @PostMapping("/flashcard")
    @Operation(summary = "生成抽认卡", description = "异步生成正反面记忆卡片，通过全局 SSE 通道推送结果")
    public Result<String> submitFlashcard(@Valid @RequestBody FlashcardRequest request) {
        return Result.success(studioServiceImpl.submit(request, ArtifactType.FLASHCARD));
    }

    @PostMapping("/mindmap")
    @Operation(summary = "生成思维导图", description = "异步生成层级思维导图，通过全局 SSE 通道推送结果")
    public Result<String> submitMindmap(@Valid @RequestBody MindmapRequest request) {
        return Result.success(studioServiceImpl.submit(request, ArtifactType.MINDMAP));
    }

    @PostMapping("/report")
    @Operation(summary = "生成报告", description = "异步生成知识报告（DOCUMENT/INTERACTIVE），通过全局 SSE 通道推送结果")
    public Result<String> submitReport(@Valid @RequestBody ReportRequest request) {
        return Result.success(studioServiceImpl.submit(request, ArtifactType.REPORT));

    }

    @PostMapping("/presentation")
    @Operation(summary = "生成演示文稿", description = "异步生成结构化幻灯片大纲，通过全局 SSE 通道推送进度与结果")
    public Result<String> submitPresentation(@Valid @RequestBody PresentationRequest request) {
        return Result.success(studioServiceImpl.submit(request, ArtifactType.PRESENTATION));
    }

    @GetMapping("/artifacts")
    @Operation(summary = "查询工件列表", description = "返回当前用户在指定知识库下生成的全部工件，可按类型过滤（用于历史作品回看）")
    public Result<List<StudioArtifact>> listArtifacts(
            @NotBlank(message = "bookId 不能为空") @RequestParam String bookId,
            @RequestParam(required = false) ArtifactType type) {
        List<StudioArtifact> artifacts = (type == null)
                ? studioServiceImpl.listByBookId(bookId)
                : studioServiceImpl.listByBookIdAndType(bookId, type);
        return Result.success(artifacts);
    }

    @GetMapping("/artifacts/history")
    @Operation(summary = "分页查询生成历史", description = "当前登录用户的工件历史列表：强制分页（单页≤100）、剥离 payload 大字段、返回列表 VO。userId 由服务端鉴权上下文取得，不接受前端传入")
    public Result<PageResult<StudioArtifactListVO>> historyArtifacts(
            @Valid StudioHistoryQueryDTO queryDTO) {
        return Result.success(studioServiceImpl.pageHistory(queryDTO));
    }

    @GetMapping("/artifacts/{id}")
    @Operation(summary = "查询工件详情", description = "根据 artifactId 获取单个工件（详情 VO，含完整 payload 大字段，唯一放行 payload 的查询接口）")
    public Result<StudioArtifactDetailVO> getArtifact(
            @NotBlank(message = "工件ID不能为空") @PathVariable String id) {
        return Result.success(studioServiceImpl.getDetail(id));
    }
}