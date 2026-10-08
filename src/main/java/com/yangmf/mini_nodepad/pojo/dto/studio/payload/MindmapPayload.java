// pojo/dto/studio/payload/MindmapPayload.java
package com.yangmf.mini_nodepad.pojo.dto.studio.payload;

import com.yangmf.mini_nodepad.enums.studio.ArtifactType;
import com.yangmf.mini_nodepad.pojo.dto.studio.llm.MindmapLlmResponse;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 思维导图工件持久化负载。
 * 复用 MindmapLlmResponse.MindNode 作为节点结构，自身仅负责 artifactType 标识与 DB 存储。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class MindmapPayload extends ArtifactPayload {

    private String appliedStructure;

    private String reasoning;

    private MindmapLlmResponse.MindNode root;

    public MindmapPayload() {
        super(ArtifactType.MINDMAP);
    }
}