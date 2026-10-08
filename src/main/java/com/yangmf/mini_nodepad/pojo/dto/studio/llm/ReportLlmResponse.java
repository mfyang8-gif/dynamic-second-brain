package com.yangmf.mini_nodepad.pojo.dto.studio.llm;

import dev.langchain4j.model.output.structured.Description;
import lombok.Data;

/**
 * 报告模块的纯净 LLM 响应 DTO。
 * 不继承 ArtifactPayload，无 @JsonTypeInfo，仅包含大模型需要生成的业务字段。
 * 注意：format、fileUrl、widgetStates 等系统字段由 Generator 在映射阶段填充，不暴露给 LLM。
 */
@Data
public class ReportLlmResponse {

    @Description("完整的 Markdown 报告正文")
    private String markdown;
}
