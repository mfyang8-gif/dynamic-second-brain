package com.yangmf.mini_nodepad.pojo.dto.studio.llm;

import dev.langchain4j.model.output.structured.Description;
import lombok.Data;

import java.util.List;

/**
 * 思维导图模块的纯净 LLM 响应 DTO。
 * 不继承 ArtifactPayload，无 @JsonTypeInfo，仅包含大模型需要生成的业务字段。
 */
@Data
public class MindmapLlmResponse {

    @Description("实际采用的结构类型，如 CATEGORICAL、LOGICAL、CHRONOLOGICAL 等")
    private String appliedStructure;

    @Description("选择该结构类型的理由说明，简短1-2句话")
    private String reasoning;

    @Description("思维导图根节点")
    private MindNode root;

    @Data
    public static class MindNode {
        @Description("节点标题，简短精炼，不超过20字")
        private String label;

        @Description("子节点列表，每层不超过6个分支，叶子节点为空数组")
        private List<MindNode> children;
    }
}
