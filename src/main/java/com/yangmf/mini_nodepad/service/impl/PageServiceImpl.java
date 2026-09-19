
package com.yangmf.mini_nodepad.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.yangmf.mini_nodepad.aiservice.PageAssistant;
import com.yangmf.mini_nodepad.context.BaseContext;
import com.yangmf.mini_nodepad.encoder.Bm25SparseEncoder;
import com.yangmf.mini_nodepad.enums.SourceTypeEnum;
import com.yangmf.mini_nodepad.exception.BusinessException;
import com.yangmf.mini_nodepad.exception.ForbiddenException;
import com.yangmf.mini_nodepad.exception.ValidationException;
import com.yangmf.mini_nodepad.mapper.PageMapper;
import com.yangmf.mini_nodepad.pojo.dto.PageIngestDTO;
import com.yangmf.mini_nodepad.pojo.dto.PageQueryDTO;
import com.yangmf.mini_nodepad.pojo.entity.Page;
import com.yangmf.mini_nodepad.pojo.vo.PageVO;
import com.yangmf.mini_nodepad.result.PageResult;
import com.yangmf.mini_nodepad.service.PageAsyncService;
import com.yangmf.mini_nodepad.service.PageService;
import com.yangmf.mini_nodepad.utils.QdrantTemplate;
import com.yangmf.mini_nodepad.utils.TextProcessManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class PageServiceImpl extends ServiceImpl<PageMapper, Page> implements PageService {

    private final PageMapper pageMapper;
    private final PageAssistant pageAssistant;
    private final PageAsyncService pageAsyncService;
    private final TextProcessManager textCleaner;
    private final QdrantTemplate qdrantTemplate;
    private final Bm25SparseEncoder bm25Encoder;

    private static final String COLLECTION_NAME = "dynamic_brain";

    /**
     * BM25 漂移阈值：累计移除的文档数达到此值后，自动触发全量重建
     */
    private static final int BM25_DRIFT_THRESHOLD = 20;

    @Override
    public void ingestPage(PageIngestDTO dto) {
        String currentUserId = getCurrentUserId();

        String fastCleanText = textCleaner.physicalClean(dto.getContent());

        Page page = new Page();
        BeanUtils.copyProperties(dto, page);
        page.setContent(fastCleanText);

        if (dto.getSourceType() != null) {
            try {
                page.setSourceType(SourceTypeEnum.valueOf(dto.getSourceType()));
            } catch (IllegalArgumentException e) {
                throw new ValidationException("不支持的来源类型: " + dto.getSourceType());
            }
        }

        boolean needAiProcess = Integer.valueOf(1).equals(dto.getAutoOptimize());
        page.setAiProcessStatus(needAiProcess ? 1 : 0);

        this.save(page);
        log.info("知识页初次录入成功，ID: {}, bookId: {}, aiStatus: {}",
                page.getId(), page.getBookId(), page.getAiProcessStatus());

        if (needAiProcess) {
            pageAsyncService.processAiIngest(page.getId(), fastCleanText, page.getBookId(), currentUserId);
        }
    }

    @Override
    public PageVO getPageById(String id) {
        Page page = getByIdWithOwnershipCheck(id);
        return convertToVO(page);
    }

    @Override
    public PageResult<PageVO> listPagesByBookId(String bookId, PageQueryDTO queryDTO) {
        String currentUserId = getCurrentUserId();

        PageHelper.startPage(queryDTO.getPage(), queryDTO.getPageSize());

        LambdaQueryWrapper<Page> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Page::getBookId, bookId)
                .inSql(Page::getBookId, ownedBookSql(currentUserId))
                .orderByDesc(Page::getCreatedAt);
        List<Page> pageList = this.list(wrapper);

        PageInfo<Page> pageInfo = new PageInfo<>(pageList);

        List<PageVO> voList = pageInfo.getList().stream()
                .map(this::convertToVO)
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
                .inSql(Page::getBookId, ownedBookSql(currentUserId))
                .set(Page::getDeleted, 1)
                .set(Page::getDeletedAt, LocalDateTime.now());
        if (!this.update(wrapper)) {
            throw new ForbiddenException("无权操作该知识页");
        }
        log.info("知识页已移入回收站，ID: {}", id);
    }

    @Override
    public void softDeletePages(List<String> ids) {
        String currentUserId = getCurrentUserId();
        LambdaUpdateWrapper<Page> wrapper = new LambdaUpdateWrapper<>();
        wrapper.in(Page::getId, ids)
                .inSql(Page::getBookId, ownedBookSql(currentUserId))
                .set(Page::getDeleted, 1)
                .set(Page::getDeletedAt, LocalDateTime.now());
        int affected = pageMapper.update(null, wrapper);
        if (affected == 0) {
            throw new ForbiddenException("无权操作这些知识页");
        }
        if (affected < ids.size()) {
            log.warn("批量软删除部分跳过，请求: {} 条，实际: {} 条，userId: {}", ids.size(), affected, currentUserId);
        }
        log.info("批量移入回收站，userId: {}, 成功: {} 条", currentUserId, affected);
    }

    @Override
    public void permanentDeletePage(String id) {
        String currentUserId = getCurrentUserId();

        cleanupQdrantAndBm25(id);

        if (pageMapper.permanentDeleteById(id, currentUserId) == 0) {
            throw new ForbiddenException("无权操作该知识页");
        }
        log.info("知识页已永久删除 | pageId={}, userId={}（Qdrant + BM25 已清理）", id, currentUserId);
    }

    @Override
    public void permanentDeletePages(List<String> ids) {
        String currentUserId = getCurrentUserId();

        for (String pageId : ids) {
            cleanupQdrantAndBm25(pageId);
        }

        int affected = pageMapper.batchPermanentDeleteByIds(ids, currentUserId);
        if (affected == 0) {
            throw new ForbiddenException("无权操作这些知识页");
        }
        if (affected < ids.size()) {
            log.warn("批量永久删除部分跳过 | 请求: {} 条，实际: {} 条，userId: {}", ids.size(), affected, currentUserId);
        }
        log.info("批量永久删除完成 | userId: {}, 成功: {} 条（Qdrant + BM25 已清理）", currentUserId, affected);
    }

    @Override
    public void restorePage(String id) {
        String currentUserId = getCurrentUserId();
        if (pageMapper.restoreById(id, currentUserId) == 0) {
            throw new ForbiddenException("无权操作该知识页");
        }
        log.info("知识页已从回收站恢复，ID: {}", id);
    }

    @Override
    public void restorePages(List<String> ids) {
        String currentUserId = getCurrentUserId();
        int affected = pageMapper.batchRestoreByIds(ids, currentUserId);
        if (affected == 0) {
            throw new ForbiddenException("无权操作这些知识页");
        }
        if (affected < ids.size()) {
            log.warn("批量恢复部分跳过，请求: {} 条，实际: {} 条，userId: {}", ids.size(), affected, currentUserId);
        }
        log.info("批量恢复，userId: {}, 成功: {} 条", currentUserId, affected);
    }

    @Override
    public PageResult<PageVO> listRecycleBin(PageQueryDTO queryDTO) {
        String currentUserId = getCurrentUserId();

        PageHelper.startPage(queryDTO.getPage(), queryDTO.getPageSize());

        List<Page> pageList = pageMapper.selectRecycleBin(currentUserId);

        PageInfo<Page> pageInfo = new PageInfo<>(pageList);

        List<PageVO> voList = pageInfo.getList().stream()
                .map(this::convertToVO)
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
        log.info("回收站已清空 | userId: {}, 共永久删除 {} 条（Qdrant + BM25 已清理）", currentUserId, count);

        if (count > 0) {
            log.info("清空回收站后，异步触发 BM25 全量重建");
            Thread.startVirtualThread(() -> {
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
            log.error("BM25 重建失败：编码器处理异常 (chunkCount={})", allRawTexts.size(), e);
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

        log.info("用户 [{}] 触发 AI 标题预览，文本长度: {}", getCurrentUserId(), safeContent.length());

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
        Page page = getByIdWithOwnershipCheck(id);

        Integer status = page.getAiProcessStatus();
        if (status != null && status == 2) {
            throw new BusinessException("该知识页已处理成功，无需重试");
        }
        if (status != null && status == 1) {
            throw new BusinessException("该知识页正在处理中，请勿重复提交");
        }

        page.setAiProcessStatus(1);
        page.setAiProcessMsg("重试中...");
        page.setUpdatedAt(LocalDateTime.now());
        pageMapper.updateById(page);

        log.info("知识页 AI 重试触发 | pageId={}, userId={}", id, currentUserId);
        pageAsyncService.processAiIngest(id, page.getContent(), page.getBookId(), currentUserId);
    }

    // ==================== 私有方法 ====================

    /**
     * 清理单个 pageId 的 Qdrant 向量 + BM25 统计（best-effort，失败不阻断主流程）
     * 执行顺序：① 取回 chunk 文本 → ② 递减 BM25 → ③ 删除 Qdrant 向量
     * 如果 BM25 漂移达到阈值，自动异步触发全量重建
     */
    private void cleanupQdrantAndBm25(String pageId) {
        List<String> chunkTexts = List.of();

        try {
            chunkTexts = qdrantTemplate.fetchChunkRawTextsByPageId(COLLECTION_NAME, pageId);
        } catch (Exception e) {
            log.warn("BM25 递减前置失败：无法取回 chunk 文本 | pageId={}，BM25 统计将产生漂移", pageId, e);
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
            log.debug("BM25 递减完成 | pageId={}, removed={}/{}", pageId, removedCount, chunkTexts.size());
        }

        try {
            qdrantTemplate.deleteByPageId(COLLECTION_NAME, pageId);
        } catch (Exception e) {
            log.error("Qdrant 向量删除失败 | pageId={}（可能残留孤儿向量）", pageId, e);
        }

        if (bm25Encoder.getDriftCount() >= BM25_DRIFT_THRESHOLD) {
            log.info("BM25 漂移达到阈值 (drift={}/{}), 异步触发全量重建",
                    bm25Encoder.getDriftCount(), BM25_DRIFT_THRESHOLD);
            Thread.startVirtualThread(() -> {
                try {
                    rebuildBm25Index();
                } catch (Exception e) {
                    log.error("BM25 自动重建失败", e);
                }
            });
        }
    }

    private String getCurrentUserId() {
        return String.valueOf(BaseContext.getCurrentId());
    }

    private String ownedBookSql(String userId) {
        return "SELECT id FROM book WHERE user_id = '" + userId + "' AND deleted = 0";
    }

    private Page getByIdWithOwnershipCheck(String id) {
        String currentUserId = getCurrentUserId();
        LambdaQueryWrapper<Page> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Page::getId, id)
                .inSql(Page::getBookId, ownedBookSql(currentUserId));
        Page page = this.getOne(wrapper);
        if (page == null) {
            throw new ForbiddenException("无权访问该知识页");
        }
        return page;
    }

    private PageVO convertToVO(Page page) {
        PageVO vo = new PageVO();
        BeanUtils.copyProperties(page, vo);
        if (page.getSourceType() != null) {
            vo.setSourceType(page.getSourceType().name());
        }
        return vo;
    }
}
