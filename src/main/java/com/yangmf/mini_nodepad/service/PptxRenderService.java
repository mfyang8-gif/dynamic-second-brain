package com.yangmf.mini_nodepad.service;

import com.yangmf.mini_nodepad.enums.studio.PresentationVisualStyleEnum;
import com.yangmf.mini_nodepad.pojo.dto.studio.payload.PresentationPayload;

/**
 * 本地物理 PPTX 渲染防腐层（SPI）。
 * V1 仅提供接口定义，不注入任何实现；V2 接入 Apache POI 模板引擎与无版权图库后由实现类接管。
 */
public interface PptxRenderService {

    /**
     * 将结构化 JSON 填入本地物理模板，并抓取配图，合成最终 PPTX。
     */
    byte[] renderToPptx(PresentationPayload payload, PresentationVisualStyleEnum visualStyle);
}