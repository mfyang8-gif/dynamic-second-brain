// service/impl/StudioServiceImpl.java
package com.yangmf.mini_nodepad.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.yangmf.mini_nodepad.ai.aiservice.ArtifactTitleAssistant;
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
import com.yangmf.mini_nodepad.pojo.dto.studio.query.StudioHistoryQueryDTO;
import com.yangmf.mini_nodepad.pojo.dto.studio.request.BaseStudioRequest;
import com.yangmf.mini_nodepad.pojo.dto.studio.request.UpdateWidgetStateReq;
import com.yangmf.mini_nodepad.pojo.entity.StudioArtifact;
import com.yangmf.mini_nodepad.pojo.vo.BookVO;
import com.yangmf.mini_nodepad.pojo.vo.StudioArtifactDetailVO;
import com.yangmf.mini_nodepad.pojo.vo.StudioArtifactListVO;
import com.yangmf.mini_nodepad.properties.PandocProperties;
import com.yangmf.mini_nodepad.result.PageResult;
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
    private final Executor titleTaskExecutor;
    private final BookService bookService;
    private final ArtifactTitleAssistant titleAssistant;
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
                             @Qualifier(ThreadPoolConfig.TITLE_TASK_EXECUTOR) Executor titleTaskExecutor,
                             BookService bookService,
                             ArtifactTitleAssistant titleAssistant,
                             AliOssUtil aliOssUtil,
                             RestTemplate restTemplate,
                             PandocProperties pandocProperties,
                             StudioProgressTicker progressTicker) {
        this.artifactMapper = artifactMapper;
        this.workspaceSseService = workspaceSseService;
        this.contextBuilder = contextBuilder;
        this.objectMapper = objectMapper;
        this.studioTaskExecutor = studioTaskExecutor;
        this.titleTaskExecutor = titleTaskExecutor;
        this.bookService = bookService;
        this.titleAssistant = titleAssistant;
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

        // 预取知识库名称：异步线程里没有 BaseContext，再去调 bookService 必抛 401 鉴权异常。
        // 因此必须在 HTTP 请求线程（verifyOwnership 之后）取好，
        // 之后一律以纯 String 经闭包下传给 executeGeneration / applyDraftTitle，
        // 异步线程内绝不再触碰 bookService。
        String bookName = resolveBookName(request.getBookId());

        StudioArtifact artifact = StudioArtifact.builder()
                .userId(String.valueOf(userId))
                .bookId(request.getBookId())
                .type(type)
                .status(ArtifactStatus.PENDING)
                // 用户填了主题就用它；没填先落类型名 —— title 列永不为空，
                // 历史列表 / 书库卡片直接可读。真正的场景化名字由两阶段命名
                // 写进 dynamic_title，并在完成时同步覆盖 title。
                .title(initialTitle(request.getTopic(), type))
                .build();
        artifactMapper.insert(artifact);
        log.info("Studio 工件已创建 | id={}, type={}, userId={}", artifact.getId(), type, userId);

        // 阶段一：场景化暂定名。与主生成并行，Best-Effort（失败仅降级，不影响生成）。
        submitDraftTitle(artifact.getId(), userId, type, request.getTopic(), bookName);

        studioTaskExecutor.execute(
                () -> executeGeneration(artifact.getId(), userId, type, request, bookName));

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

    /** 单页请求量硬上限（红线：防恶意大 pageSize 拉爆内存；DTO 校验之外的兜底） */
    private static final int HISTORY_MAX_PAGE_SIZE = 100;

    /**
     * 生成历史分页查询（列表）—— 四条红线的落点：
     *
     * 1. 防越权：userId 只从 BaseContext（HTTP 线程上下文）取，
     *    入参模型里根本没有 userId 字段，前端传什么都不看；
     *    传了 bookId 还会先 verifyOwnership，确保这个书架也确实是你的。
     * 2. 防 OOM：MyBatis-Plus Page 强制分页（拦截器负责拼 LIMIT），
     *    pageSize 再兜一次 ≤100 的上限（防绕过 Bean Validation 的内部调用）。
     * 3. 省 IO：.select(...) 显式列清单，payload 多态大文本在 SQL 层就不取。
     * 4. 模型分离：查出来的是实体，出去的是列表 VO —— 大字段想漏都漏不出去。
     */
    @Override
    public PageResult<StudioArtifactListVO> pageHistory(StudioHistoryQueryDTO queryDTO) {
        Long userId = BaseContext.getCurrentId();

        int page = Math.max(queryDTO.getPage(), 1);
        int pageSize = Math.min(
                Math.max(queryDTO.getPageSize(), 1),
                HISTORY_MAX_PAGE_SIZE
        );

        String bookId = queryDTO.getBookId();
        if (bookId != null && !bookId.isBlank()) {
            // 过滤了书架，就先确认书架是当前用户的（防越权枚举他人 bookId）
            bookService.verifyOwnership(bookId, userId);
        }

        Page<StudioArtifact> p = new Page<>(page, pageSize);
        Page<StudioArtifact> result = artifactMapper.selectPage(p,
                new LambdaQueryWrapper<StudioArtifact>()
                        // 鉴权 ID 一律取自线程上下文，绝不信任前端
                        .eq(StudioArtifact::getUserId, String.valueOf(userId))
                        .eq(bookId != null && !bookId.isBlank(), StudioArtifact::getBookId, bookId)
                        .eq(queryDTO.getType() != null, StudioArtifact::getType, queryDTO.getType())
                        // 大字段剥离：列表查询明确不要 payload（多态 JSON 大文本）
                        .select(StudioArtifact::getId,
                                StudioArtifact::getBookId,
                                StudioArtifact::getType,
                                StudioArtifact::getStatus,
                                StudioArtifact::getTitle,
                                StudioArtifact::getDynamicTitle,
                                StudioArtifact::getCreatedAt,
                                StudioArtifact::getUpdatedAt)
                        .orderByDesc(StudioArtifact::getCreatedAt));

        List<StudioArtifactListVO> records = result.getRecords().stream()
                .map(StudioArtifactListVO::from)
                .toList();

        return PageResult.<StudioArtifactListVO>builder()
                .total(result.getTotal())
                .page(page)
                .pageSize(pageSize)
                .records(records)
                .build();
    }

    /**
     * 生成历史详情：唯一允许返回 payload 大字段的查询。
     * 归属校验复用 getById（内部已按当前鉴权 ID 过滤，查不到即 404 语义）。
     */
    @Override
    public StudioArtifactDetailVO getDetail(String artifactId) {
        return StudioArtifactDetailVO.from(getById(artifactId));
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
                                   ArtifactType type, BaseStudioRequest request,
                                   String bookName) {
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

                // 阶段二：精修命名。
                // 刻意收敛在【主线程单线程路径】内：只在内存对象上 set，不做任何独立落库。
                // 下面那次 updateById 是本次生成唯一写库点，
                // 否则会与暂定名线程争抢同一行，互相覆盖 status / payload。
                String refined = refineTitle(type, request.getTopic(), bookName, payload);
                String finalTitle = (refined != null && !refined.isBlank())
                        ? refined
                        : fallbackTitle(request.getTopic(), bookName, type);
                artifact.setDynamicTitle(finalTitle);
                // title 列同步写回：书库 / 历史列表读的就是这一列。
                // 只写 dynamic_title 会让「刷新页面后的列表」退回类型名（用户实测的 bug）。
                artifact.setTitle(finalTitle);

                artifact.setStatus(ArtifactStatus.SUCCESS);
                artifact.setPayload(payload);
                // 本次生成唯一写库点。
                // 注意：若精修失败，artifact.dynamicTitle 仍是 null，
                // MyBatis-Plus 默认的 NOT_NULL 字段策略会把该列从 SET 里剔除，
                // 因此不会把暂定名线程刚写进去的值覆盖成 NULL。
                artifactMapper.updateById(artifact);
            }

            // 先推终值标题，再推完成事件，避免前端出现"先完成、后改名"的闪烁
            if (artifact.getDynamicTitle() != null) {
                workspaceSseService.pushToUser(userId,
                        WorkspaceEvent.artifactTitled(artifactId, artifact.getDynamicTitle()));
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

    /* ==================== 动态命名（dynamic_title） ==================== */

    /**
     * 在 HTTP 请求线程内预取知识库名称，供两阶段命名作为上下文。
     * 失败退化为空串（命名不带库名），绝不阻断提交。
     */
    private String resolveBookName(String bookId) {
        try {
            BookVO book = bookService.getBookById(bookId);
            return (book != null && book.getName() != null) ? book.getName() : "";
        } catch (Exception e) {
            log.warn("预取知识库名称失败，命名将不带库名 | bookId={}", bookId, e);
            return "";
        }
    }

    /**
     * 阶段一派发：严格 Best-Effort。
     *
     * <p>专用池 titleTaskExecutor 是 AbortPolicy，满载时抛 RejectedExecutionException，
     * 连同其他一切异常在此静默吞掉 —— 前端拿不到暂定名会自动回退显示 topic。
     */
    private void submitDraftTitle(String artifactId, Long userId, ArtifactType type,
                                  String topic, String bookName) {
        try {
            titleTaskExecutor.execute(
                    () -> applyDraftTitle(artifactId, userId, type, topic, bookName));
        } catch (Exception e) {
            log.warn("暂定名任务提交失败，已忽略（前端回退显示 topic）| id={}", artifactId, e);
        }
    }

    /**
     * 阶段一落库：只更新 dynamic_title 单列。
     *
     * <p>① 用 update(null, wrapper) 单列写，绝不用 updateById 全行写 ——
     * 全行写会把主线程刚写进去的 status / payload 覆盖掉（脏写、工件假死）。
     *
     * <p>② 状态闸门：仅当工件仍处于 PENDING / PROCESSING 时才写暂定名。
     * 若主生成已 SUCCESS（精修名随同一次 updateById 落库）或已 FAILED，
     * 则 rows 为 0 直接放弃，杜绝"暂定名迟到反杀精修名"。
     */
    private void applyDraftTitle(String artifactId, Long userId, ArtifactType type,
                                 String topic, String bookName) {
        try {
            String draft = titleAssistant.nameIt(
                    "DRAFT", type.getDescription(), bookName, safeTopic(topic), "");
            String cleaned = cleanTitle(draft);
            if (cleaned.isEmpty()) {
                return;
            }
            int rows = artifactMapper.update(null, new LambdaUpdateWrapper<StudioArtifact>()
                    .eq(StudioArtifact::getId, artifactId)
                    .in(StudioArtifact::getStatus, ArtifactStatus.PENDING, ArtifactStatus.PROCESSING)
                    .set(StudioArtifact::getDynamicTitle, cleaned));
            if (rows == 0) {
                log.debug("工件已不在生成中，跳过暂定名写入 | id={}", artifactId);
                return;
            }
            workspaceSseService.pushToUser(userId,
                    WorkspaceEvent.artifactTitled(artifactId, cleaned));
            log.info("暂定名已应用 | id={}, title={}", artifactId, cleaned);
        } catch (Exception e) {
            // 异步线程内任何异常都不许冒泡，否则会被线程池的未捕获处理器接管
            log.warn("暂定名生成失败，已忽略 | id={}", artifactId, e);
        }
    }

    /** 阶段二：主线程内精修命名。失败返回 null，保留暂定名或 topic。 */
    private String refineTitle(ArtifactType type, String topic, String bookName,
                               ArtifactPayload payload) {
        try {
            String refined = titleAssistant.nameIt(
                    "FINAL", type.getDescription(), bookName, safeTopic(topic), digestOf(payload));
            return cleanTitle(refined);
        } catch (Exception e) {
            log.warn("精修命名失败，保留暂定名 | type={}", type, e);
            return null;
        }
    }

    /** 成品 JSON 节选，作为精修命名的内容依据（截断避免撑爆 Prompt）。 */
    private String digestOf(ArtifactPayload payload) {
        if (payload == null) {
            return "";
        }
        try {
            String json = objectMapper.writeValueAsString(payload).replaceAll("\\s+", " ").trim();
            return json.length() > 1500 ? json.substring(0, 1500) : json;
        } catch (Exception e) {
            log.warn("工件内容摘要序列化失败，精修命名将只依据用户诉求", e);
            return "";
        }
    }

    /** topic 判空兜底，防止 null 或空白污染 Prompt。 */
    private String safeTopic(String topic) {
        return (topic == null || topic.isBlank())
                ? "（用户未指定主题，请依据工件内容自行提炼）"
                : topic.trim();
    }

    /** 初始标题：topic 优先，缺省落类型名 —— 保证 title 列永不为空。 */
    private String initialTitle(String topic, ArtifactType type) {
        return (topic == null || topic.isBlank()) ? type.getDescription() : topic.trim();
    }

    /**
     * 命名链路全失败时的静态兜底名：topic → 「书名 · 类型名」→ 类型名。
     *
     * <p>存在的意义是「双保险」：AI 命名可能因限流 / 超时 / 返回空串而失败，
     * 此时若 title 与 dynamic_title 双双为空，前端就只能显示空标题或
     * 「XXX生成中」这种占位名。这里保证任何一条路径都能落一个可读的名字。
     */
    private String fallbackTitle(String topic, String bookName, ArtifactType type) {
        if (topic != null && !topic.isBlank()) {
            return topic.trim();
        }
        if (bookName != null && !bookName.isBlank()) {
            return bookName.trim() + " · " + type.getDescription();
        }
        return type.getDescription();
    }

    /** 标题清洗：去引号/书名号/换行与模型常用前缀，截断到列宽以内。 */
    private String cleanTitle(String raw) {
        if (raw == null) {
            return "";
        }
        String t = raw.replaceAll("[《》\"'`\\r\\n]", "")
                .replaceAll("\\s+", " ")
                .replaceFirst("^(标题|名称)\\s*[:：]\\s*", "")
                .trim();
        return t.length() > 120 ? t.substring(0, 120) : t;
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