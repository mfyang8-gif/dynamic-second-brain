package com.yangmf.mini_nodepad.pojo.dto.studio.llm;

import dev.langchain4j.model.output.structured.Description;
import lombok.Data;

import java.util.List;

/**
 * 抽认卡模块的纯净 LLM 响应 DTO。
 * 不继承 ArtifactPayload，无 @JsonTypeInfo，仅包含大模型需要生成的业务字段。
 */
@Data
public class FlashcardLlmResponse {

    @Description("抽认卡列表")
    private List<Card> cards;

    @Data
    public static class Card {
        @Description("卡片正面：一个明确的问题或概念提示")
        private String front;

        @Description("卡片背面：简洁精准的答案或解释，不超过100字")
        private String back;

        @Description("分类标签，用于前端分组显示，如'定义'、'公式'、'案例'、'人物'")
        private String category;
    }
}
