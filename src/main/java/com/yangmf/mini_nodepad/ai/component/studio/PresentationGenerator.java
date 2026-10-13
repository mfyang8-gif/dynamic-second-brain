package com.yangmf.mini_nodepad.ai.component.studio;

import com.yangmf.mini_nodepad.ai.aiservice.PresentationStudioAssistant;
import com.yangmf.mini_nodepad.enums.studio.ArtifactType;
import com.yangmf.mini_nodepad.pojo.dto.studio.llm.PresentationLlmResponse;
import com.yangmf.mini_nodepad.pojo.dto.studio.payload.PresentationPayload;
import com.yangmf.mini_nodepad.pojo.dto.studio.payload.StudioProgressPayload;
import com.yangmf.mini_nodepad.pojo.dto.studio.request.PresentationRequest;

import com.yangmf.mini_nodepad.service.PptxRenderService;
import com.yangmf.mini_nodepad.utils.AliOssUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class PresentationGenerator extends AbstractStudioGenerator<PresentationRequest, PresentationPayload> {

    private final PresentationStudioAssistant assistant;
    private final AliOssUtil aliOssUtil;

    /** 允许为空：V1 阶段平滑过渡，未实装物理渲染时返回纯 JSON */
    @Autowired(required = false)
    private PptxRenderService pptxRenderService;

    @Override
    public ArtifactType getType() {
        return ArtifactType.PRESENTATION;
    }

    /** PPT 专属的沉浸式进度动画帧 */
    @Override
    public List<StudioProgressPayload> progressTimeline() {
        // 7 帧：观点 → 故事线 → 每页要点 → 视觉排版 → 配图 → 装配 → 校对（2~3 秒一格）
        return List.of(
                new StudioProgressPayload("EXTRACT", "正在提取知识库核心观点...", 8),
                new StudioProgressPayload("PLAN", "正在规划幻灯片故事线...", 24),
                new StudioProgressPayload("OUTLINE", "正在敲定每页要点...", 42),
                new StudioProgressPayload("LAYOUT", "正在为您提炼每页视觉排版...", 60),
                new StudioProgressPayload("IMAGE", "正在为您检索高清商业配图...", 76),
                new StudioProgressPayload("ASSEMBLE", "正在装配整套演示...", 88),
                new StudioProgressPayload("FINALIZE", "正在做最后校对...", 93)
        );
    }

    @Override
    protected PresentationPayload doGenerate(PresentationRequest request, String context) {
        String prompt = String.format("""
        【参数配置】
        - 篇幅: %s | 受众: %s | 风格: %s
        【源知识库内容】
        %s
        """,
                request.getLengthLevel().getDescription(),
                request.getTargetAudience().getDescription(),
                request.getVisualStyle().getDescription(),
                context);

        log.info("开始生成结构化幻灯片大纲 JSON...");
        PresentationLlmResponse response = assistant.generatePresentation(prompt);

        // 显式搬运：LLM 纯净响应 → 持久化 Payload
        PresentationPayload payload = new PresentationPayload();
        payload.setMainTitle(response.getMainTitle());
        payload.setSlides(response.getSlides());

        // 若未来实装了本地物理渲染服务，则自动接管生成实体文件
        if (pptxRenderService != null) {
            try {
                log.info("检测到 PptxRenderService，开始组装本地物理 PPTX...");
                byte[] pptxBytes = pptxRenderService.renderToPptx(payload, request.getVisualStyle());
                String objectName = aliOssUtil.generateFileName("presentation.pptx");
                payload.setFileUrl(aliOssUtil.upload(pptxBytes, "studio/presentations/" + objectName));
            } catch (Exception e) {
                log.error("渲染物理 PPTX 失败，已降级为纯 JSON 模式", e);
            }
        } else {
            log.info("尚未实装物理渲染引擎，返回 JSON 供前端预览。");
        }

        return payload;
    }
}
