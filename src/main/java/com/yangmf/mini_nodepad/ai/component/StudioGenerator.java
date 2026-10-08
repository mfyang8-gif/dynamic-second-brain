package com.yangmf.mini_nodepad.ai.component;

import com.yangmf.mini_nodepad.enums.studio.ArtifactType;

import com.yangmf.mini_nodepad.pojo.dto.studio.payload.ArtifactPayload;
import com.yangmf.mini_nodepad.pojo.dto.studio.payload.StudioProgressPayload;
import com.yangmf.mini_nodepad.pojo.dto.studio.request.BaseStudioRequest;

import java.util.List;

public interface StudioGenerator<REQ extends BaseStudioRequest, RES extends ArtifactPayload> {

    ArtifactType getType();

    RES generate(REQ request, String context);

    /**
     * 该模块专属的沉浸式进度动画帧序列。
     * 默认返回空列表：不需要进度推送的模块零侵入，行为完全不变。
     */
    default List<StudioProgressPayload> progressTimeline() {
        return List.of();
    }
}