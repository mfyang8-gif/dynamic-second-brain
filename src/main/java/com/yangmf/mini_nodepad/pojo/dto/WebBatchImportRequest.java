package com.yangmf.mini_nodepad.pojo.dto;

import com.yangmf.mini_nodepad.pojo.vo.WebSearchResultVO;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

@Data
@Schema(name = "联网搜索批量导入请求")
public class WebBatchImportRequest {

    @NotBlank(message = "bookId 不能为空")
    @Schema(description = "目标知识库ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private String bookId;

    @Schema(description = "是否开启AI自动清洗 0-关闭 1-开启，默认1")
    private Integer autoOptimize = 1;

    @Schema(description = "是否允许AI修改 0-不允许 1-允许，默认1")
    private Integer allowAiModify = 1;

    @NotEmpty(message = "请至少勾选一条搜索结果")
    @Valid
    @Schema(description = "用户勾选后提交的候选条目", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<WebSearchResultVO> items;
}