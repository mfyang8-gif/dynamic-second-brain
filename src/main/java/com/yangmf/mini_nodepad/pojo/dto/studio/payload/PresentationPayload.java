package com.yangmf.mini_nodepad.pojo.dto.studio.payload;

import com.yangmf.mini_nodepad.enums.studio.ArtifactType;
import com.yangmf.mini_nodepad.pojo.dto.studio.llm.PresentationLlmResponse;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 演示文稿工件持久化负载。
 * 复用 PresentationLlmResponse.SlideNode 作为幻灯片结构，自身仅负责 artifactType 标识与 DB 存储。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class PresentationPayload extends ArtifactPayload {

    public PresentationPayload() {
        super(ArtifactType.PRESENTATION);
    }

    private String mainTitle;

    private List<PresentationLlmResponse.SlideNode> slides;

    private String fileUrl;
}