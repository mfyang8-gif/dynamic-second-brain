package com.yangmf.mini_nodepad.result;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "批量操作结果")
public class BatchOperationResult {

    @Schema(description = "请求操作数量")
    private int requested;

    @Schema(description = "实际成功数量")
    private int succeeded;

    @Schema(description = "跳过数量（无权限或不存在）")
    private int skipped;

    @Schema(description = "提示信息")
    private String message;

    public static BatchOperationResult of(int requested, int succeeded) {
        int skipped = requested - succeeded;
        String msg = skipped == 0
                ? "操作成功"
                : String.format("请求 %d 条，成功 %d 条，跳过 %d 条（无权限或不存在）", requested, succeeded, skipped);
        return BatchOperationResult.builder()
                .requested(requested)
                .succeeded(succeeded)
                .skipped(skipped)
                .message(msg)
                .build();
    }
}