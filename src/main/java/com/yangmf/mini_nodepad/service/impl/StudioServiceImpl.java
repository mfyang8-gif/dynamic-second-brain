// service/impl/StudioServiceImpl.java
package com.yangmf.mini_nodepad.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.yangmf.mini_nodepad.ai.component.StudioGenerator;
import com.yangmf.mini_nodepad.config.ThreadPoolConfig;
import com.yangmf.mini_nodepad.context.BaseContext;
import com.yangmf.mini_nodepad.enums.studio.ArtifactStatus;
import com.yangmf.mini_nodepad.enums.studio.ArtifactType;
import com.yangmf.mini_nodepad.enums.studio.WidgetStatusEnum;
import com.yangmf.mini_nodepad.exception.BusinessException;
import com.yangmf.mini_nodepad.exception.ResourceNotFoundException;
import com.yangmf.mini_nodepad.mapper.StudioArtifactMapper;
import com.yangmf.mini_nodepad.pojo.dto.WorkspaceEvent;

import com.yangmf.mini_nodepad.pojo.dto.studio.payload.ArtifactPayload;
import com.yangmf.mini_nodepad.pojo.dto.studio.payload.ReportPayload;
import com.yangmf.mini_nodepad.pojo.dto.studio.payload.StudioProgressPayload;
import com.yangmf.mini_nodepad.pojo.dto.studio.request.BaseStudioRequest;
import com.yangmf.mini_nodepad.pojo.dto.studio.request.UpdateWidgetStateReq;
import com.yangmf.mini_nodepad.pojo.entity.StudioArtifact;
import com.yangmf.mini_nodepad.properties.PandocProperties;
import com.yangmf.mini_nodepad.service.BookService;
import com.yangmf.mini_nodepad.ai.component.StudioContextBuilder;
import com.yangmf.mini_nodepad.service.StudioService;
import com.yangmf.mini_nodepad.service.WorkspaceSseService;
import com.yangmf.mini_nodepad.service.studio.StudioProgressTicker;
import com.yangmf.mini_nodepad.utils.AliOssUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class StudioServiceImpl implements StudioService {

    private final StudioArtifactMapper artifactMapper;
    private final WorkspaceSseService workspaceSseService;
    private final StudioContextBuilder contextBuilder;
    private final ObjectMapper objectMapper;
    private final Map<ArtifactType, StudioGenerator<?, ?>> generatorMap;
    private final Executor studioTaskExecutor;
    private final BookService bookService;
    private final AliOssUtil aliOssUtil;
    private final RestTemplate restTemplate;
    private final PandocProperties pandocProperties;

    private final StudioProgressTicker progressTicker;

    private static final Pattern WIDGET_PATTERN = Pattern.compile(
            "\\[WIDGET:(?<type>\\w+),\\s*id=\"(?<id>[^\"]+)\",\\s*title=\"(?<title>[^\"]+)\",\\s*prompt=\"(?<prompt>[^\"]+)\"\\]"
    );

    public StudioServiceImpl(StudioArtifactMapper artifactMapper,
                             WorkspaceSseService workspaceSseService,
                             StudioContextBuilder contextBuilder,
                             ObjectMapper objectMapper,
                             List<StudioGenerator<?, ?>> generators,
                             @Qualifier(ThreadPoolConfig.STUDIO_TASK_EXECUTOR) Executor studioTaskExecutor,
                             BookService bookService,
                             AliOssUtil aliOssUtil,
                             RestTemplate restTemplate,
                             PandocProperties pandocProperties,
                             StudioProgressTicker progressTicker) {
        this.artifactMapper = artifactMapper;
        this.workspaceSseService = workspaceSseService;
        this.contextBuilder = contextBuilder;
        this.objectMapper = objectMapper;
        this.studioTaskExecutor = studioTaskExecutor;
        this.bookService = bookService;
        this.aliOssUtil = aliOssUtil;
        this.restTemplate = restTemplate;
        this.pandocProperties = pandocProperties;
        this.progressTicker = progressTicker;
        this.generatorMap = new EnumMap<>(ArtifactType.class);
        for (StudioGenerator<?, ?> generator : generators) {
            this.generatorMap.put(generator.getType(), generator);
        }
    }
    @Override
    public String submit(BaseStudioRequest request, ArtifactType type) {
        Long userId = BaseContext.getCurrentId();

        long activeCount = artifactMapper.selectCount(
                new LambdaQueryWrapper<StudioArtifact>()
                        .eq(StudioArtifact::getUserId, String.valueOf(userId))
                        .in(StudioArtifact::getStatus, ArtifactStatus.PENDING, ArtifactStatus.PROCESSING)
        );
        if (activeCount >= 3) {
            log.warn("用户提交频率过高 | userId={}, activeCount={}", userId, activeCount);
            throw new BusinessException("您当前已有 3 个任务正在生成中，请等待完成后再提交");
        }
        bookService.verifyOwnership(request.getBookId(), userId);
        StudioArtifact artifact = StudioArtifact.builder()
                .userId(String.valueOf(userId))
                .bookId(request.getBookId())
                .type(type)
                .status(ArtifactStatus.PENDING)
                .title(request.getTopic())
                .build();
        artifactMapper.insert(artifact);
        log.info("Studio 工件已创建 | id={}, type={}, userId={}", artifact.getId(), type, userId);

        studioTaskExecutor.execute(() -> executeGeneration(artifact.getId(), userId, type, request));

        return artifact.getId();
    }

    @Override
    public StudioArtifact getById(String artifactId) {
        Long userId = BaseContext.getCurrentId();
        StudioArtifact artifact = artifactMapper.selectOne(
                new LambdaQueryWrapper<StudioArtifact>()
                        .eq(StudioArtifact::getId, artifactId)
                        .eq(StudioArtifact::getUserId, String.valueOf(userId))
        );
        if (artifact == null) {
            throw new ResourceNotFoundException("工件不存在或无权访问");
        }
        return artifact;
    }

    @Override
    public List<StudioArtifact> listByBookId(String bookId) {
        Long userId = BaseContext.getCurrentId();
        bookService.verifyOwnership(bookId, userId);
        return artifactMapper.selectList(
                new LambdaQueryWrapper<StudioArtifact>()
                        .eq(StudioArtifact::getBookId, bookId)
                        .eq(StudioArtifact::getUserId, String.valueOf(userId))
                        .orderByDesc(StudioArtifact::getCreatedAt)
        );
    }

    @Override
    public List<StudioArtifact> listByBookIdAndType(String bookId, ArtifactType type) {
        Long userId = BaseContext.getCurrentId();
        bookService.verifyOwnership(bookId, userId);
        return artifactMapper.selectList(
                new LambdaQueryWrapper<StudioArtifact>()
                        .eq(StudioArtifact::getBookId, bookId)
                        .eq(StudioArtifact::getType, type)
                        .eq(StudioArtifact::getUserId, String.valueOf(userId))
                        .orderByDesc(StudioArtifact::getCreatedAt)
        );
    }

    @Override
    public void deleteById(String artifactId) {
        Long userId = BaseContext.getCurrentId();
        int rows = artifactMapper.delete(
                new LambdaQueryWrapper<StudioArtifact>()
                        .eq(StudioArtifact::getId, artifactId)
                        .eq(StudioArtifact::getUserId, String.valueOf(userId))
        );
        if (rows == 0) {
            throw new ResourceNotFoundException("工件不存在或无权操作");
        }
    }

    @Override
    public void updateWidgetState(String reportArtifactId, String widgetId, UpdateWidgetStateReq req) {
        Long userId = BaseContext.getCurrentId();
        StudioArtifact artifact = artifactMapper.selectById(reportArtifactId);

        if (artifact == null || !String.valueOf(userId).equals(artifact.getUserId())) {
            throw new ResourceNotFoundException("报告不存在或无权操作");
        }

        ArtifactPayload rawPayload = artifact.getPayload();
        if (!(rawPayload instanceof ReportPayload reportPayload)) {
            throw new BusinessException("该工件不是报告类型");
        }

        if (reportPayload.getWidgetStates() == null || !reportPayload.getWidgetStates().containsKey(widgetId)) {
            throw new BusinessException("指定的 widgetId 不存在: " + widgetId);
        }

        ReportPayload.WidgetState state = reportPayload.getWidgetStates().get(widgetId);
        state.setStatus(req.getStatus());
        if (req.getTargetArtifactId() != null) {
            state.setTargetArtifactId(req.getTargetArtifactId());
        }

        artifactMapper.updateById(artifact);
        log.info("Widget 状态已更新 | reportId={}, widgetId={}, newStatus={}",
                reportArtifactId, widgetId, req.getStatus());
    }

    @Override
    public String exportReportAsDocx(String reportArtifactId) {
        Long userId = BaseContext.getCurrentId();
        StudioArtifact artifact = artifactMapper.selectById(reportArtifactId);

        if (artifact == null || !String.valueOf(userId).equals(artifact.getUserId())) {
            throw new ResourceNotFoundException("报告不存在或无权操作");
        }

        ArtifactPayload rawPayload = artifact.getPayload();
        if (!(rawPayload instanceof ReportPayload reportPayload)) {
            throw new BusinessException("该工件不是报告类型");
        }

        String cleanedMarkdown = cleanWidgetsForExport(reportPayload);

        try {
            Map<String, String> requestBody = new HashMap<>();
            requestBody.put("markdown", cleanedMarkdown);

            byte[] docxBytes = restTemplate.postForObject(
                    pandocProperties.getApiUrl(),
                    requestBody,
                    byte[].class
            );

            if (docxBytes == null || docxBytes.length == 0) {
                throw new BusinessException("导出转换失败，Pandoc 服务返回为空");
            }

            String objectName = aliOssUtil.generateFileName("report-export.docx");
            String fileUrl = aliOssUtil.upload(docxBytes, "studio/reports/" + objectName);

            log.info("Report 导出 DOCX 成功 | reportId={}, fileUrl={}", reportArtifactId, fileUrl);
            return fileUrl;

        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("导出 DOCX 渲染或上传失败", e);
            throw new BusinessException("导出文档失败: " + e.getMessage());
        }
    }

    private String cleanWidgetsForExport(ReportPayload reportPayload) {
        String markdown = reportPayload.getMarkdown();
        if (markdown == null) return "";

        Map<String, ReportPayload.WidgetState> widgetStates = reportPayload.getWidgetStates();
        Matcher matcher = WIDGET_PATTERN.matcher(markdown);
        StringBuilder sb = new StringBuilder();

        while (matcher.find()) {
            String widgetId = matcher.group("id");
            String title = matcher.group("title");
            ReportPayload.WidgetState state = (widgetStates != null) ? widgetStates.get(widgetId) : null;
            String replacement;

            if (state == null || state.getStatus() == WidgetStatusEnum.DISMISSED) {
                replacement = "";
            } else if (state.getStatus() == WidgetStatusEnum.RECOMMENDED) {
                replacement = "> **互动提示**：Web端包含对应的沉浸式互动学习组件（" + title + "）。";
            } else if (state.getStatus() == WidgetStatusEnum.GENERATED_SHOW
                    || state.getStatus() == WidgetStatusEnum.GENERATED_HIDE) {
                replacement = "> **互动组件**：" + title + "（已生成）";
            } else {
                replacement = "> **互动提示**：" + title;
            }

            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }


    @SuppressWarnings({"unchecked", "rawtypes"})
    private void executeGeneration(String artifactId, Long userId,
                                   ArtifactType type, BaseStudioRequest request) {
        try {
            StudioArtifact artifact = artifactMapper.selectById(artifactId);
            if (artifact == null) {
                log.error("工件记录不存在 | id={}", artifactId);
                return;
            }

            artifact.setStatus(ArtifactStatus.PROCESSING);
            artifactMapper.updateById(artifact);

            StudioGenerator generator = generatorMap.get(type);
            if (generator == null) {
                throw new IllegalStateException("未找到类型 " + type + " 的生成器");
            }

            // 取出该模块专属的进度时间线，交由 Ticker 在黑盒期间轮播
            List<StudioProgressPayload> timeline = generator.progressTimeline();

            ArtifactPayload payload;
            // try-with-resources：LLM 一返回（或抛异常），Ticker 必定被掐死
            try (AutoCloseable ticker = progressTicker.start(userId, artifactId, timeline)) {
                // 真实耗时 1：构建上下文（RAG / 全文检索）
                String context = contextBuilder.buildContext(request);
                // 真实耗时 2：LLM 阻塞生成（黑盒期间 Ticker 在后台轮播进度帧）
                payload = generator.generate(request, context);

                artifact.setStatus(ArtifactStatus.SUCCESS);
                artifact.setPayload(payload);
                artifactMapper.updateById(artifact);
            }

            String payloadJson = objectMapper.writeValueAsString(payload);
            workspaceSseService.pushToUser(userId,
                    WorkspaceEvent.artifactCompleted(artifactId, payloadJson));

            log.info("Studio 工件生成成功 | id={}, type={}", artifactId, type);

        } catch (Exception e) {
            log.error("Studio 工件生成失败 | id={}, type={}", artifactId, type, e);
            markFailed(artifactId, e);
            workspaceSseService.pushToUser(userId,
                    WorkspaceEvent.artifactFailed(artifactId, "生成失败，请稍后重试"));
        }
    }

    private void markFailed(String artifactId, Exception e) {
        try {
            StudioArtifact artifact = artifactMapper.selectById(artifactId);
            if (artifact != null) {
                artifact.setStatus(ArtifactStatus.FAILED);
                String errMsg = e.getMessage();
                artifact.setErrorMsg(errMsg != null && errMsg.length() > 490
                        ? errMsg.substring(0, 490) : errMsg);
                artifactMapper.updateById(artifact);
            }
        } catch (Exception ex) {
            log.error("更新失败状态时异常 | id={}", artifactId, ex);
        }
    }
}