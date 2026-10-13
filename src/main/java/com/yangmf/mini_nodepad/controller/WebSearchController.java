package com.yangmf.mini_nodepad.controller;

import com.yangmf.mini_nodepad.enums.ResultCodeEnum;
import com.yangmf.mini_nodepad.exception.BusinessException;
import com.yangmf.mini_nodepad.pojo.vo.WebSearchResultVO;
import com.yangmf.mini_nodepad.result.Result;
import com.yangmf.mini_nodepad.service.WebSearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/v1/search")
@RequiredArgsConstructor
@Validated
@Tag(name = "联网搜索", description = "对标 NotebookLM 的先搜索候选、后勾选导入两步走机制")
public class WebSearchController {

    private final WebSearchService webSearchService;

    @GetMapping("/query")
    @Operation(summary = "联网搜索候选（只查不落库）",
            description = "MCP 直连召回 → 域名打散 → Rerank 精排，返回 top 候选卡片供前端勾选")
    public Result<List<WebSearchResultVO>> query(
            @RequestParam @NotBlank(message = "搜索关键词不能为空") String keyword,
            @RequestParam(value = "limit", required = false) Integer limit) {
        try {
            return Result.success(webSearchService.query(keyword, limit));
        } catch (BusinessException e) {
            // 软失败：HTTP 200 + 业务错误 code，避免 500 吓到前端
            log.warn("联网搜索业务异常 | keyword={}, msg={}", keyword, e.getMessage());
            return Result.error(ResultCodeEnum.WEB_SEARCH_UNAVAILABLE.getCode(), e.getMessage());
        }
    }
}