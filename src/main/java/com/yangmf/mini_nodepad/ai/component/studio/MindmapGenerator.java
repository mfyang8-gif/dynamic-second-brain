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
        // 6 帧：把「梳理 → 聚类 → 建骨架 → 展开 → 补细节 → 精简」的推理过程显式铺开，
        // 保证 2~3 秒就有一格新进展（旧版 3 帧 = 8 秒一跳，观感是「卡住」）
        return List.of(
                new StudioProgressPayload("EXTRACT", "正在梳理知识脉络...", 10),
                new StudioProgressPayload("CLUSTER", "正在归并主题聚类...", 26),
                new StudioProgressPayload("STRUCTURE", "正在构建层级骨架...", 44),
                new StudioProgressPayload("EXPAND", "正在展开分支节点...", 62),
                new StudioProgressPayload("DETAIL", "正在补充节点细节...", 78),
                new StudioProgressPayload("PRUNE", "正在精简分支与节点...", 90)
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
