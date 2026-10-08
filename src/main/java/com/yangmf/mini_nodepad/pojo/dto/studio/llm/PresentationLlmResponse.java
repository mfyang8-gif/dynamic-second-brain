package com.yangmf.mini_nodepad.pojo.dto.studio.llm;

import com.yangmf.mini_nodepad.enums.studio.SlideLayoutEnum;
import dev.langchain4j.model.output.structured.Description;
import lombok.Data;

import java.util.List;

/**
 * 演示文稿模块的纯净 LLM 响应 DTO。
 * 不继承 ArtifactPayload，无 @JsonTypeInfo，仅包含大模型需要生成的业务字段。
 * 注意：fileUrl 等系统字段由 Generator 在映射阶段填充，不暴露给 LLM。
 */
@Data
public class PresentationLlmResponse {

    @Description("演示文稿主标题")
    private String mainTitle;

    @Description("幻灯片页面列表")
    private List<SlideNode> slides;

    @Data
    public static class SlideNode {
        @Description("页码，从 1 开始")
        private Integer pageNumber;

        @Description("页面版式标记：COVER(封面页), BULLET_LIST(图文列表), COMPARISON(双列对比), QUOTE(金句突出)")
        private SlideLayoutEnum layoutType;

        @Description("本页标题")
        private String title;

        @Description("幻灯片核心要点。严格限制：最多4条，每条必须是极简短语，不超过20字")
        private List<String> bulletPoints;

        @Description("配图检索关键词（英文），提炼本页核心意象，用于无版权图库检索")
        private String imageKeywords;

        @Description("演讲者备注。包含所有被删减的详细解释、推导过程及口语化表述")
        private String speakerNotes;
    }
}
