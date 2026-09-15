package com.yangmf.mini_nodepad.pojo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(name = "知识页录入请求")
public class PageIngestDTO {

    @NotBlank(message = "bookId 不能为空")
    @Schema(description = "所属书本ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private String bookId;

    @NotBlank(message = "标题不能为空")
    @Size(max = 200, message = "标题不能超过200个字符")
    @Schema(description = "知识页标题", requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 200)
    private String title;

    @NotBlank(message = "内容不能为空")
    @Size(max = 10000, message = "内容不能超过10000个字符")
    @Schema(description = "知识页正文内容", requiredMode = Schema.RequiredMode.REQUIRED)
    private String content;

    @Schema(description = "知识页摘要")
    @Size(max = 500, message = "摘要不能超过500个字符")
    private String summary;

    @Schema(description = "来源类型")
    private String sourceType;

    @Schema(description = "是否开启AI自动清洗 0-关闭 1-开启，默认1")
    private Integer autoOptimize;

    @Schema(description = "是否允许AI修改 0-不允许 1-允许，默认1")
    private Integer allowAiModify;


}