package com.yangmf.mini_nodepad.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.yangmf.mini_nodepad.ai.aiservice.PageAssistant;
import com.yangmf.mini_nodepad.context.BaseContext;
import com.yangmf.mini_nodepad.converter.PageConverter;
import com.yangmf.mini_nodepad.ai.rag.Bm25SparseEncoder;
import com.yangmf.mini_nodepad.enums.AiProcessStatusEnum;
import com.yangmf.mini_nodepad.enums.SourceTypeEnum;
import com.yangmf.mini_nodepad.exception.BusinessException;
import com.yangmf.mini_nodepad.exception.ForbiddenException;
import com.yangmf.mini_nodepad.exception.ValidationException;
import com.yangmf.mini_nodepad.mapper.PageMapper;
import com.yangmf.mini_nodepad.pojo.dto.PageIngestDTO;
import com.yangmf.mini_nodepad.pojo.dto.PageQueryDTO;
import com.yangmf.mini_nodepad.pojo.entity.Page;
import com.yangmf.mini_nodepad.pojo.vo.PageVO;
import com.yangmf.mini_nodepad.result.BatchOperationResult;
import com.yangmf.mini_nodepad.result.PageResult;
import com.yangmf.mini_nodepad.ai.rag.PageAsyncService;
import com.yangmf.mini_nodepad.service.PageService;
import com.yangmf.mini_nodepad.ai.rag.QdrantTemplate;
import com.yangmf.mini_nodepad.ai.component.TextProcessManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class PageServiceImpl extends ServiceImpl<PageMapper, Page> implements PageService {

    private final PageMapper pageMapper;
    private final PageAssistant pageAssistant;
    private final PageAsyncService pageAsyncService;
    private final TextProcessManager textCleaner;
    private final QdrantTemplate qdrantTemplate;
    private final Bm25SparseEncoder bm25Encoder;
    private final Executor aiTaskExecutor;
    private final StringRedisTemplate stringRedisTemplate;
    private final PageConverter pageConverter;

    @Value("${bm25.drift-threshold:20}")
    private int bm25DriftThreshold;

    @Value("${ai.retry-lock-seconds:10}")
    private int aiRetryLockSeconds;

    private static final String COLLECTION_NAME = "dynamic_brain";

    private static final String OWNED_BOOK_SUB_QUERY =
            "book_id IN (SELECT id FROM book WHERE user_id = {0} AND deleted = 0)";

    private static final String AI_RETRY_LOCK_PREFIX = "ai:retry:lock:";

    public PageServiceImpl(PageMapper pageMapper,
                           PageAssistant pageAssistant,
                           PageAsyncService pageAsyncService,
                           TextProcessManager textCleaner,
                           QdrantTemplate qdrantTemplate,
                           Bm25SparseEncoder bm25Encoder,
                           @Qualifier("aiTaskExecutor") Executor aiTaskExecutor,
                           StringRedisTemplate stringRedisTemplate,
                           PageConverter pageConverter) {
        this.pageMapper = pageMapper;
        this.pageAssistant = pageAssistant;
        this.pageAsyncService = pageAsyncService;
        this.textCleaner = textCleaner;
        this.qdrantTemplate = qdrantTemplate;
        this.bm25Encoder = bm25Encoder;
        this.aiTaskExecutor = aiTaskExecutor;
        this.stringRedisTemplate = stringRedisTemplate;
        this.pageConverter = pageConverter;
    }

    @Override
    public void ingestPage(PageIngestDTO dto) {
        String currentUserId = getCurrentUserId();

        String fastCleanText = textCleaner.physicalClean(dto.getContent());

        String contentHash = sha256(fastCleanText);

        LambdaQueryWrapper<Page> dupCheck = new LambdaQueryWrapper<>();
        dupCheck.eq(Page::getBookId, dto.getBookId())
                .eq(Page::getContentHash, contentHash)
                .eq(Page::getDeleted, 0);
        Page existingPage = this.getOne(dupCheck);
        if (existingPage != null) {
            log.warn("检测到重复内容，拒绝入库 | bookId={}, existingPageId={}, existingTitle={}, hash={}",
                    dto.getBookId(), existingPage.getId(), existingPage.getTitle(), contentHash.substring(0, 12));
            throw new ValidationException(
                    "该内容已在知识库中存在（「" + existingPage.getTitle() + "」），无需重复添加");
        }

        Page page = pageConverter.toEntity(dto);
        page.setContent(fastCleanText);
        page.setContentHash(contentHash);

        if (dto.getSourceType() != null) {
            try {
                page.setSourceType(SourceTypeEnum.valueOf(dto.getSourceType()));
            } catch (IllegalArgumentException e) {
                throw new ValidationException("不支持的来源类型: " + dto.getSourceType());
            }
        }

        boolean needAiProcess = Integer.valueOf(1).equals(dto.getAutoOptimize());
        page.setAiProcessStatus(needAiProcess ? AiProcessStatusEnum.PROCESSING : AiProcessStatusEnum.PENDING);

        try {
            this.save(page);
        } catch (DuplicateKeyException e) {
            log.warn("并发拦截：重复内容入库被数据库拒绝 | bookId={}, hash={}",
                    dto.getBookId(), contentHash.substring(0, 12));
            throw new ValidationException("内容重复，请勿重复提交");
        }

        log.info("知识页初次录入成功 | pageId={}, bookId={}, aiStatus={}, hash={}",
                page.getId(), page.getBookId(), page.getAiProcessStatus(), contentHash.substring(0, 12));

        if (needAiProcess) {
            pageAsyncService.processAiIngest(page.getId(), fastCleanText, page.getBookId(), currentUserId);
        }
    }

    @Override
    public PageVO getPageById(String id) {
        Page page = getByIdWithOwnershipCheck(id);
        return pageConverter.toVO(page);
    }

    @Override
    public PageResult<PageVO> listPagesByBookId(String bookId, PageQueryDTO queryDTO) {
        String currentUserId = getCurrentUserId();

        PageHelper.startPage(queryDTO.getPage(), queryDTO.getPageSize());

        LambdaQueryWrapper<Page> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Page::getBookId, bookId)
                .apply(OWNED_BOOK_SUB_QUERY, currentUserId)
                .orderByDesc(Page::getCreatedAt);
        List<Page> pageList = this.list(wrapper);

        PageInfo<Page> pageInfo = new PageInfo<>(pageList);

        List<PageVO> voList = pageInfo.getList().stream()
                .map(pageConverter::toVO)
                .toList();

        return PageResult.<PageVO>builder()
                .total(pageInfo.getTotal())
                .page(queryDTO.getPage())
                .pageSize(queryDTO.getPageSize())
                .records(voList)
                .build();
    }

    @Override
    public void softDeletePage(String id) {
        String currentUserId = getCurrentUserId();
        LambdaUpdateWrapper<Page> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(Page::getId, id)
                .apply(OWNED_BOOK_SUB_QUERY, currentUserId)
                .set(Page::getDeleted, 1)
                .set(Page::getDeletedAt, LocalDateTime.now());
        if (!this.update(wrapper)) {
            throw new ForbiddenException("无权操作该知识页");
        }
        log.info("知识页已移入回收站 | pageId={}, userId={}", id, currentUserId);
    }

    @Override
    public BatchOperationResult softDeletePages(List<String> ids) {
        String currentUserId = getCurrentUserId();
        LambdaUpdateWrapper<Page> wrapper = new LambdaUpdateWrapper<>();
        wrapper.in(Page::getId, ids)
                .apply(OWNED_BOOK_SUB_QUERY, currentUserId)
                .set(Page::getDeleted, 1)
                .set(Page::getDeletedAt, LocalDateTime.now());
        int affected = pageMapper.update(null, wrapper);
        if (affected == 0) {
            throw new ForbiddenException("无权操作这些知识页");
        }
        log.info("批量移入回收站 | userId={}, requested={}, succeeded={}", currentUserId, ids.size(), affected);
        return BatchOperationResult.of(ids.size(), affected);
    }

    @Override
    public void permanentDeletePage(String id) {
        String currentUserId = getCurrentUserId();

        cleanupQdrantAndBm25(id);

        if (pageMapper.permanentDeleteById(id, currentUserId) == 0) {
            throw new ForbiddenException("无权操作该知识页");
        }
        log.info("知识页已永久删除 | pageId={}, userId={}", id, currentUserId);
    }

    @Override
    public BatchOperationResult permanentDeletePages(List<String> ids) {
        String currentUserId = getCurrentUserId();

        for (String pageId : ids) {
            cleanupQdrantAndBm25(pageId);
        }

        int affected = pageMapper.batchPermanentDeleteByIds(ids, currentUserId);
        if (affected == 0) {
            throw new ForbiddenException("无权操作这些知识页");
        }
        log.info("批量永久删除完成 | userId={}, requested={}, succeeded={}", currentUserId, ids.size(), affected);
        return BatchOperationResult.of(ids.size(), affected);
    }

    @Override
    public void restorePage(String id) {
        String currentUserId = getCurrentUserId();
        if (pageMapper.restoreById(id, currentUserId) == 0) {
            throw new ForbiddenException("无权操作该知识页");
        }
        log.info("知识页已从回收站恢复 | pageId={}, userId={}", id, currentUserId);
    }

    @Override
    public BatchOperationResult restorePages(List<String> ids) {
        String currentUserId = getCurrentUserId();
        int affected = pageMapper.batchRestoreByIds(ids, currentUserId);
        if (affected == 0) {
            throw new ForbiddenException("无权操作这些知识页");
        }
        log.info("批量恢复完成 | userId={}, requested={}, succeeded={}", currentUserId, ids.size(), affected);
        return BatchOperationResult.of(ids.size(), affected);
    }

    @Override
    public PageResult<PageVO> listRecycleBin(PageQueryDTO queryDTO) {
        String currentUserId = getCurrentUserId();

        PageHelper.startPage(queryDTO.getPage(), queryDTO.getPageSize());

        List<Page> pageList = pageMapper.selectRecycleBin(currentUserId);

        PageInfo<Page> pageInfo = new PageInfo<>(pageList);

        List<PageVO> voList = pageInfo.getList().stream()
                .map(pageConverter::toVO)
                .toList();

        return PageResult.<PageVO>builder()
                .total(pageInfo.getTotal())
                .page(queryDTO.getPage())
                .pageSize(queryDTO.getPageSize())
                .records(voList)
                .build();
    }

    @Override
    public void emptyRecycleBin() {
        String currentUserId = getCurrentUserId();

        List<Page> recycleBinPages = pageMapper.selectRecycleBin(currentUserId);
        if (!recycleBinPages.isEmpty()) {
            for (Page page : recycleBinPages) {
                cleanupQdrantAndBm25(page.getId());
            }
        }

        int count = pageMapper.permanentDeleteRecycleBin(currentUserId);
        log.info("回收站已清空 | userId={}, deletedCount={}", currentUserId, count);

        if (count > 0) {
            log.info("清空回收站后异步触发 BM25 全量重建");
            aiTaskExecutor.execute(() -> {
                try {
                    bm25Encoder.resetDriftCounter();
                    rebuildBm25Index();
                } catch (Exception e) {
                    log.error("BM25 清空回收站后自动重建失败", e);
                }
            });
        }
    }

    @Override
    public void rebuildBm25Index() {
        log.info("BM25 全量重建触发");

        List<String> allRawTexts;
        try {
            allRawTexts = qdrantTemplate.fetchChunkRawTextsByPageId(COLLECTION_NAME, null);
        } catch (Exception e) {
            log.error("BM25 重建失败：无法从 Qdrant 获取 chunk 数据", e);
            throw new BusinessException("BM25 重建失败：向量库查询异常", e);
        }

        if (allRawTexts.isEmpty()) {
            log.warn("Qdrant 中无 chunk 数据，BM25 将被重置为空表");
            bm25Encoder.reset();
            return;
        }

        try {
            bm25Encoder.rebuildFromChunks(allRawTexts);
        } catch (Exception e) {
            log.error("BM25 重建失败：编码器处理异常 | chunkCount={}", allRawTexts.size(), e);
            throw new BusinessException("BM25 重建失败：编码器处理异常", e);
        }

        log.info("BM25 全量重建完成 | chunks={}", allRawTexts.size());
    }

    @Override
    public String generateTitlePreview(String content) {
        if (content == null || content.trim().length() < 5) {
            log.warn("文本太短，无法生成标题");
            throw new ValidationException("文本太短，无法生成标题");
        }

        String safeContent = content.length() > 10000 ? content.substring(0, 10000) : content;

        log.info("触发 AI 标题预览 | userId={}, textLength={}", getCurrentUserId(), safeContent.length());

        try {
            return pageAssistant.generateTitle(safeContent);
        } catch (Exception e) {
            log.warn("AI 生成标题失败", e);
            throw new ValidationException("AI 生成标题失败", e);
        }
    }

    @Override
    public String generateSummaryPreview(String content) {
        if (content == null || content.trim().length() < 10) {
            log.warn("文本太短，无法生成摘要");
            throw new ValidationException("文本太短，无法生成摘要");
        }
        String safeContent = content.length() > 3000 ? content.substring(0, 3000) : content;

        try {
            return pageAssistant.generateSummary(safeContent);
        } catch (Exception e) {
            log.warn("AI 生成摘要失败", e);
            throw new ValidationException("AI 生成摘要失败", e);
        }
    }

    @Override
    public void retryAiProcess(String id) {
        String currentUserId = getCurrentUserId();
        String lockKey = AI_RETRY_LOCK_PREFIX + id;

        Boolean locked = stringRedisTemplate.opsForValue()
                .setIfAbsent(lockKey, "1", aiRetryLockSeconds, TimeUnit.SECONDS);
        if (locked == null || !locked) {
            throw new BusinessException("该知识页正在处理中，请勿重复提交");
        }

        try {
            Page page = getByIdWithOwnershipCheck(id);

            AiProcessStatusEnum status = page.getAiProcessStatus();
            if (status == AiProcessStatusEnum.SUCCESS) {
                throw new BusinessException("该知识页已处理成功，无需重试");
            }
            if (status == AiProcessStatusEnum.PROCESSING) {
                throw new BusinessException("该知识页正在处理中，请勿重复提交");
            }

            page.setAiProcessStatus(AiProcessStatusEnum.PROCESSING);
            page.setAiProcessMsg("重试中...");
            page.setUpdatedAt(LocalDateTime.now());
            pageMapper.updateById(page);

            log.info("知识页 AI 重试触发 | pageId={}, userId={}", id, currentUserId);
            pageAsyncService.processAiIngest(id, page.getContent(), page.getBookId(), currentUserId);
        } finally {
            try {
                stringRedisTemplate.delete(lockKey);
            } catch (Exception e) {
                log.warn("AI 重试锁释放失败 | lockKey={}", lockKey, e);
            }
        }
    }

    // ==================== 私有方法 ====================

    private void cleanupQdrantAndBm25(String pageId) {
        List<String> chunkTexts = List.of();

        try {
            chunkTexts = qdrantTemplate.fetchChunkRawTextsByPageId(COLLECTION_NAME, pageId);
        } catch (Exception e) {
            log.warn("BM25 递减前置失败 | pageId={}, msg=BM25 统计将产生漂移", pageId, e);
        }

        if (!chunkTexts.isEmpty()) {
            int removedCount = 0;
            for (String text : chunkTexts) {
                try {
                    bm25Encoder.removeDocument(text);
                    removedCount++;
                } catch (Exception e) {
                    log.warn("BM25 removeDocument 失败 | pageId={}, chunkIndex={}", pageId, removedCount, e);
                }
            }
            log.debug("BM25 递减完成 | pageId={}, removed={}, total={}", pageId, removedCount, chunkTexts.size());
        }

        try {
            qdrantTemplate.deleteByPageId(COLLECTION_NAME, pageId);
        } catch (Exception e) {
            log.error("Qdrant 向量删除失败 | pageId={}", pageId, e);
        }

        if (bm25Encoder.getDriftCount() >= bm25DriftThreshold) {
            log.info("BM25 漂移达到阈值 | drift={}, threshold={}，异步触发全量重建",
                    bm25Encoder.getDriftCount(), bm25DriftThreshold);
            aiTaskExecutor.execute(() -> {
                try {
                    rebuildBm25Index();
                } catch (Exception e) {
                    log.error("BM25 自动重建失败", e);
                }
            });
        }
    }
    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 算法不可用", e);
        }
    }

    private String getCurrentUserId() {
        return String.valueOf(BaseContext.getCurrentId());
    }

    private Page getByIdWithOwnershipCheck(String id) {
        String currentUserId = getCurrentUserId();
        LambdaQueryWrapper<Page> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Page::getId, id)
                .apply(OWNED_BOOK_SUB_QUERY, currentUserId);
        Page page = this.getOne(wrapper);
        if (page == null) {
            throw new ForbiddenException("无权访问该知识页");
        }
        return page;
    }
}