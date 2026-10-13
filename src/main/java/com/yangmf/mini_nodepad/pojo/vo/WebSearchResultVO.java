package com.yangmf.mini_nodepad.pojo.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "联网搜索候选结果")
public class WebSearchResultVO {

    @Schema(description = "网页标题")
    private String title;
    @NotBlank(message = "网页 URL 不能为空")
    @Schema(description = "网页链接")
    private String url;

    @Schema(description = "网页摘要/核心内容片段")
    private String snippet;

    /**
     * 发布时间（原始字符串，不做格式假设）。
     *
     * <p>2026-10-12 新增（用户裁决的「以最新时间线为准」）：网页侧时间此前被解析层
     * <b>主动丢弃</b>——{@code parsePages} 只抓 title/url/snippet，MCP 若返回
     * {@code published_time} 之类字段也进不来。
     *
     * <p>本字段为 null 表示上游未提供 → 上下文按「本轮实时检索」退化标注
     * （用户已明确接受该退化），靠"实时 vs 笔记更新于 X"的相对比较做冲突裁决。
     */
    private String publishedTime;

    public WebSearchResultVO(String title, String url, String snippet) {
        this.title = title;
        this.url = url;
        this.snippet = snippet;
    }
}