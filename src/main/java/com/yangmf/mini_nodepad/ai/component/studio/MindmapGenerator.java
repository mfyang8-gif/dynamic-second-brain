// ai/component/studio/MindmapGenerator.java
package com.yangmf.mini_nodepad.ai.component.studio;

import com.yangmf.mini_nodepad.ai.aiservice.MindmapStudioAssistant;
import com.yangmf.mini_nodepad.enums.studio.ArtifactType;
import com.yangmf.mini_nodepad.pojo.dto.studio.llm.MindmapLlmResponse;
import com.yangmf.mini_nodepad.pojo.dto.studio.payload.MindmapPayload;
import com.yangmf.mini_nodepad.pojo.dto.studio.payload.StudioProgressPayload;
import com.yangmf.mini_nodepad.pojo.dto.studio.request.MindmapRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class MindmapGenerator extends AbstractStudioGenerator<MindmapRequest, MindmapPayload> {

    private final MindmapStudioAssistant mindmapAssistant;

    @Override
    public List<StudioProgressPayload> progressTimeline() {
        return List.of(
                new StudioProgressPayload("EXTRACT", "正在梳理知识脉络...", 20),
                new StudioProgressPayload("STRUCTURE", "正在构建层级导图...", 60),
                new StudioProgressPayload("PRUNE", "正在精简分支与节点...", 88)
        );
    }

    @Override
    public ArtifactType getType() {
        return ArtifactType.MINDMAP;
    }

    @Override
    protected MindmapPayload doGenerate(MindmapRequest request, String context) {
        String prompt = String.format("""
        【参数】深度: %s | 结构偏好: %s | 语言: %s
        
        【知识库文档内容】
        %s
        """, request.getDepthLevel(), request.getStructureType(), request.getLanguage(), context);

        MindmapLlmResponse response = mindmapAssistant.generateMindmap(prompt);

        MindmapPayload payload = new MindmapPayload();
        payload.setAppliedStructure(response.getAppliedStructure());
        payload.setReasoning(response.getReasoning());
        payload.setRoot(response.getRoot());
        return payload;
    }
}
