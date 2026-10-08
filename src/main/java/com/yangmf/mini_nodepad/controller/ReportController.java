package com.yangmf.mini_nodepad.controller;

import com.yangmf.mini_nodepad.pojo.dto.studio.request.UpdateWidgetStateReq;
import com.yangmf.mini_nodepad.result.Result;
import com.yangmf.mini_nodepad.service.StudioService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/studio/report")
@RequiredArgsConstructor
@Tag(name = "Report 报告专属接口", description = "互动式报告的组件状态管理与 DOCX 导出")
public class ReportController {

    private final StudioService studioServiceImpl;

    @PutMapping("/{reportArtifactId}/widget/{widgetId}/state")
    @Operation(summary = "更新互动组件状态",
            description = "局部更新 INTERACTIVE 报告中指定 widget 的状态")
    public Result<Void> updateWidgetState(
            @PathVariable String reportArtifactId,
            @PathVariable String widgetId,
            @Valid @RequestBody UpdateWidgetStateReq req) {
        studioServiceImpl.updateWidgetState(reportArtifactId, widgetId, req);
        return Result.success();
    }

    @PostMapping("/{reportArtifactId}/export-docx")
    @Operation(summary = "导出 DOCX",
            description = "将报告（含互动占位符清洗）渲染为 DOCX 物理文件并返回 OSS 链接")
    public Result<String> exportDocx(@PathVariable String reportArtifactId) {
        String fileUrl = studioServiceImpl.exportReportAsDocx(reportArtifactId);
        return Result.success(fileUrl);
    }
}