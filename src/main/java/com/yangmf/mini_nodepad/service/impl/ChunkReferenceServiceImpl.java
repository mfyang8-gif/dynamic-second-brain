package com.yangmf.mini_nodepad.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yangmf.mini_nodepad.ai.rag.QdrantTemplate;
import com.yangmf.mini_nodepad.context.BaseContext;
import com.yangmf.mini_nodepad.exception.ForbiddenException;
import com.yangmf.mini_nodepad.exception.ValidationException;
import com.yangmf.mini_nodepad.mapper.PageMapper;
import com.yangmf.mini_nodepad.pojo.entity.Page;
import com.yangmf.mini_nodepad.pojo.vo.ChunkContextVO;
import com.yangmf.mini_nodepad.service.ChunkReferenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChunkReferenceServiceImpl implements ChunkReferenceService {

    private final PageMapper pageMapper;
    private final QdrantTemplate qdrantTemplate;

    private static final String COLLECTION_NAME = "dynamic_brain";

    private static final int PREVIEW_LENGTH = 100;

    private static final String OWNED_BOOK_SUB_QUERY =
            "book_id IN (SELECT id FROM book WHERE user_id = {0} AND deleted = 0)";

    @Override
    public ChunkContextVO getChunkContext(String pageId, Integer chunkIndex) {
        String currentUserId = String.valueOf(BaseContext.getCurrentId());

        // 红线1&2：userId 只从上下文取；不存在/不属于当前用户统一 403，防资源探测
        Page page = pageMapper.selectOne(
                new LambdaQueryWrapper<Page>()
                        .select(Page::getId, Page::getBookId, Page::getChunkCount)
                        .eq(Page::getId, pageId)
                        .apply(OWNED_BOOK_SUB_QUERY, currentUserId)
        );
        if (page == null) {
            throw new ForbiddenException("无权访问该知识页");
        }

        // 降级防御：解析失败/未入向量库的页面没有可引用的分块
        if (page.getChunkCount() == null || page.getChunkCount() == 0) {
            throw new ValidationException("该来源尚未完成向量化，暂不可引用原文");
        }
        if (chunkIndex < 0 || chunkIndex >= page.getChunkCount()) {
            throw new ValidationException("分块序号越界");
        }

        Map<Integer, String> chunks = qdrantTemplate.fetchChunkTextsByIndexes(
                COLLECTION_NAME, pageId, List.of(chunkIndex - 1, chunkIndex, chunkIndex + 1));

        String content = chunks.get(chunkIndex);
        if (content == null) {
            log.warn("MySQL 与 Qdrant 分块状态不一致 | pageId={}, chunkIndex={}, chunkCount={}",
                    pageId, chunkIndex, page.getChunkCount());
            throw new ValidationException("该分块数据不可用，请重新导入来源");
        }

        return ChunkContextVO.builder()
                .pageId(pageId)
                .chunkIndex(chunkIndex)
                .totalChunks(page.getChunkCount())
                .content(content)
                .prevChunkPreview(tailPreview(chunks.get(chunkIndex - 1)))
                .nextChunkPreview(headPreview(chunks.get(chunkIndex + 1)))
                .build();
    }

    private String tailPreview(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        return text.length() <= PREVIEW_LENGTH ? text : text.substring(text.length() - PREVIEW_LENGTH);
    }

    private String headPreview(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        return text.length() <= PREVIEW_LENGTH ? text : text.substring(0, PREVIEW_LENGTH);
    }
}
