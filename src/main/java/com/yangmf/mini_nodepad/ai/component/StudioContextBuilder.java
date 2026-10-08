// ai/component/StudioContextBuilder.java
package com.yangmf.mini_nodepad.ai.component;

import com.yangmf.mini_nodepad.ai.rag.PageRagRetrievalService;
import com.yangmf.mini_nodepad.mapper.PageMapper;
import com.yangmf.mini_nodepad.pojo.dto.studio.request.BaseStudioRequest;
import com.yangmf.mini_nodepad.pojo.entity.Page;
// 假设你的异常类是这个，如果没有请替换为你的全局业务异常
import com.yangmf.mini_nodepad.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class StudioContextBuilder {

    private final PageMapper pageMapper;
    private final PageRagRetrievalService ragService;

    @Value("${studio.context.max-length:20000}")
    private int contextMaxLength;

    public String buildContext(BaseStudioRequest request) {
        List<String> pageIds = request.getSourcePageIds();
        String context;

        if (pageIds != null && !pageIds.isEmpty()) {
            context = buildFromFullPages(pageIds, request.getTopic());
        } else {
            context = buildFromRag(request);
        }

        // 统一的【空上下文全局防御】
        if (context == null || context.isBlank()) {
            log.warn("构建上下文失败：知识库中暂无可用内容 | bookId={}, pageIds={}", request.getBookId(), pageIds);
            throw new BusinessException("所选文档暂无可用内容，请确保文档已解析完毕且不为空");
        }

        return context;
    }

    private String buildFromFullPages(List<String> pageIds, String topic) {
        List<Page> pages = pageMapper.selectBatchIds(pageIds);
        if (pages.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        boolean isTruncated = false;

        for (Page page : pages) {
            if (page.getContent() == null || page.getContent().isBlank()) {
                continue;
            }

            sb.append("### ").append(page.getTitle() != null ? page.getTitle() : "未命名文档").append("\n");
            sb.append(page.getContent().trim()).append("\n\n");

            // OOM 内存防御：提前截断，不加载多余的长文档
            if (sb.length() >= contextMaxLength) {
                isTruncated = true;
                break;
            }
        }

        String context = sb.toString().trim();

        // 长度裁剪与优雅截断标记
        if (context.length() > contextMaxLength) {
            context = context.substring(0, contextMaxLength);
            isTruncated = true;
        }

        if (isTruncated) {
            context += "\n\n... (出于安全长度控制，底层数据流已被截断)";
            log.info("全文上下文触发截断保护 | originalPages={}, max={}", pages.size(), contextMaxLength);
        }

        return context;
    }

    private String buildFromRag(BaseStudioRequest request) {
        String searchQuery = (request.getTopic() != null && !request.getTopic().isBlank())
                ? request.getTopic()
                : "核心知识点 重要概念 关键内容 整体框架";

        var chunks = ragService.retrieveChunks(searchQuery, request.getBookId(), null, 12);

        // 如果 Qdrant 没有搜到内容（chunks 为空），直接返回空字符串，交由顶层触发空上下文异常
        if (chunks == null || chunks.isEmpty()) {
            return "";
        }

        String context = ragService.buildContextString(chunks);
        log.info("Studio 上下文构建完成（Qdrant 降级）| chunks={}, length={}", chunks.size(), context.length());

        return context;
    }
}