package com.yangmf.mini_nodepad.pojo.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@Schema(name = "引用原文上下文")
public class ChunkContextVO {

    @Schema(description = "知识页ID")
    private String pageId;

    @Schema(description = "当前分块序号")
    private Integer chunkIndex;

    @Schema(description = "总分块数，供前端渲染 3/12 类定位标识")
    private Integer totalChunks;

    @Schema(description = "当前分块完整原文")
    private String content;

    @Schema(description = "上一分块末尾100字预览，无上一块时为 null")
    private String prevChunkPreview;

    @Schema(description = "下一分块开头100字预览，无下一块时为 null")
    private String nextChunkPreview;
}
