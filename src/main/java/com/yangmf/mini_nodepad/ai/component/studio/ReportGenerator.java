package com.yangmf.mini_nodepad.ai.component.studio;

import com.yangmf.mini_nodepad.ai.aiservice.ReportStudioAssistant;
import com.yangmf.mini_nodepad.enums.studio.ArtifactType;
import com.yangmf.mini_nodepad.enums.studio.ReportFormatEnum;
import com.yangmf.mini_nodepad.enums.studio.WidgetStatusEnum;
import com.yangmf.mini_nodepad.exception.BusinessException;
import com.yangmf.mini_nodepad.pojo.dto.studio.llm.ReportLlmResponse;
import com.yangmf.mini_nodepad.pojo.dto.studio.payload.ReportPayload;
import com.yangmf.mini_nodepad.pojo.dto.studio.payload.StudioProgressPayload;
import com.yangmf.mini_nodepad.pojo.dto.studio.request.ReportRequest;
import com.yangmf.mini_nodepad.properties.PandocProperties;
import com.yangmf.mini_nodepad.utils.AliOssUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.List;

@Slf4j
@Component
public class ReportGenerator extends AbstractStudioGenerator<ReportRequest, ReportPayload> {

    private final ReportStudioAssistant reportAssistant;
    private final AliOssUtil aliOssUtil;
    private final RestTemplate restTemplate;
    private final PandocProperties pandocProperties;

    private static final Pattern WIDGET_PATTERN = Pattern.compile(
            "\\[WIDGET:(?<type>\\w+),\\s*id=\"(?<id>[^\"]+)\",\\s*title=\"(?<title>[^\"]+)\",\\s*prompt=\"(?<prompt>[^\"]+)\"\\]"
    );

    public ReportGenerator(ReportStudioAssistant reportAssistant,
                           AliOssUtil aliOssUtil,
                           RestTemplate restTemplate,
                           PandocProperties pandocProperties) {
        this.reportAssistant = reportAssistant;
        this.aliOssUtil = aliOssUtil;
        this.restTemplate = restTemplate;
        this.pandocProperties = pandocProperties;
    }

    @Override
    public List<StudioProgressPayload> progressTimeline() {
        return List.of(
                new StudioProgressPayload("EXTRACT", "正在通读知识库全文...", 15),
                new StudioProgressPayload("OUTLINE", "正在规划报告章节大纲...", 45),
                new StudioProgressPayload("WRITE", "正在撰写章节正文...", 75),
                new StudioProgressPayload("POLISH", "正在润色排版与总结...", 92)
        );
    }

    @Override
    public ArtifactType getType() {
        return ArtifactType.REPORT;
    }

    @Override
    protected ReportPayload doGenerate(ReportRequest request, String context) {
        ReportFormatEnum format = request.getFormat();

        if (format == ReportFormatEnum.DOCUMENT) {
            return generateDocument(request, context);
        } else {
            return generateInteractive(request, context);
        }
    }

    private ReportPayload generateDocument(ReportRequest request, String context) {
        String prompt = String.format("""
        【参数】输出形态: DOCUMENT | 模板偏好: %s | 语言: %s
        
        【知识库文档内容】
        %s
        """, request.getTemplateFocus(), request.getLanguage(), context);

        ReportLlmResponse response = reportAssistant.generateDocumentReport(prompt);

        ReportPayload payload = new ReportPayload();
        payload.setMarkdown(response.getMarkdown());
        payload.setFormat(ReportFormatEnum.DOCUMENT);

        try {
            Map<String, String> requestBody = new HashMap<>();
            requestBody.put("markdown", payload.getMarkdown());

            byte[] docxBytes = restTemplate.postForObject(
                    pandocProperties.getApiUrl(),
                    requestBody,
                    byte[].class
            );

            if (docxBytes == null || docxBytes.length == 0) {
                throw new BusinessException("文档转换失败，Pandoc 服务返回为空");
            }

            String objectName = aliOssUtil.generateFileName("report.docx");
            String fileUrl = aliOssUtil.upload(docxBytes, "studio/reports/" + objectName);
            payload.setFileUrl(fileUrl);

        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Pandoc 渲染或 OSS 上传失败", e);
            throw new BusinessException("生成 Word 文档失败: " + e.getMessage());
        }

        log.info("Report DOCUMENT 生成完成 | markdown长度={}, fileUrl={}",
                payload.getMarkdown() != null ? payload.getMarkdown().length() : 0,
                payload.getFileUrl());
        return payload;
    }

    private ReportPayload generateInteractive(ReportRequest request, String context) {
        String prompt = String.format("""
        【参数】输出形态: INTERACTIVE | 模板偏好: %s | 语言: %s
        
        【知识库文档内容】
        %s
        """, request.getTemplateFocus(), request.getLanguage(), context);

        ReportLlmResponse response = reportAssistant.generateInteractiveReport(prompt);

        ReportPayload payload = new ReportPayload();
        payload.setMarkdown(response.getMarkdown());
        payload.setFormat(ReportFormatEnum.INTERACTIVE);

        Map<String, ReportPayload.WidgetState> widgetStates = parseWidgetStates(response.getMarkdown());
        payload.setWidgetStates(widgetStates);

        log.info("Report INTERACTIVE 生成完成 | widgetCount={}, markdown长度={}",
                widgetStates.size(),
                payload.getMarkdown() != null ? payload.getMarkdown().length() : 0);
        return payload;
    }

    private Map<String, ReportPayload.WidgetState> parseWidgetStates(String markdown) {
        Map<String, ReportPayload.WidgetState> states = new LinkedHashMap<>();
        if (markdown == null || markdown.isBlank()) {
            return states;
        }

        Matcher matcher = WIDGET_PATTERN.matcher(markdown);
        while (matcher.find()) {
            String widgetId = matcher.group("id");
            states.put(widgetId, ReportPayload.WidgetState.builder()
                    .status(WidgetStatusEnum.RECOMMENDED)
                    .targetArtifactId(null)
                    .build());
        }
        return states;
    }
}
