package com.yangmf.mini_nodepad.controller;

import com.yangmf.mini_nodepad.context.BaseContext;
import com.yangmf.mini_nodepad.pojo.vo.SemanticHabitVO;
import com.yangmf.mini_nodepad.result.Result;
import com.yangmf.mini_nodepad.service.SemanticMemoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 全局语义记忆管理（Phase 3 缺口 1：习惯可视化与遗忘权闭环）。
 * userId 一律从 BaseContext 解析（红线 1），归属校验下沉 Service 层（红线 2）。
 */
@RestController
@RequestMapping("/api/v1/memory")
@RequiredArgsConstructor
@Validated
@Tag(name = "全局记忆", description = "用户全局习惯的查看与删除接口")
public class SemanticMemoryController {

    private final SemanticMemoryService semanticMemoryService;

    @GetMapping("/habits")
    @Operation(summary = "列出我的全局习惯（按最近强化时间倒序）")
    public Result<List<SemanticHabitVO>> listHabits() {
        return Result.success(semanticMemoryService.listHabits(BaseContext.getCurrentId()));
    }

    @DeleteMapping("/habits/{id}")
    @Operation(summary = "删除一条习惯（逻辑删除，删除后不会被后台扫描复活）")
    public Result<Void> deleteHabit(@NotNull(message = "习惯ID不能为空") @PathVariable Long id) {
        semanticMemoryService.deleteHabit(BaseContext.getCurrentId(), id);
        return Result.success();
    }
}
