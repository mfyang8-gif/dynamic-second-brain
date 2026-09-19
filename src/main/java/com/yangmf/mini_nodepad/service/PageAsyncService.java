package com.yangmf.mini_nodepad.service;

import com.yangmf.mini_nodepad.aiservice.PageAssistant;
import com.yangmf.mini_nodepad.config.ThreadPoolConfig;
import com.yangmf.mini_nodepad.mapper.PageMapper;
import com.yangmf.mini_nodepad.pojo.entity.Page;
import com.yangmf.mini_nodepad.utils.QdrantTemplate;
import com.yangmf.mini_nodepad.utils.TextProcessManager;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class PageAsyncService {

    private final TextProcessManager textProcessManager;
    private final PageAssistant pageAssistant;
    private final QdrantTemplate qdrantTemplate;
    private final PageMapper pageMapper;

    private static final String COLLECTION_NAME = "dynamic_brain";

    private static final int CHUNK_SIZE = 500;
    private static final int CHUNK_OVERLAP = 50;

    @Async(ThreadPoolConfig.AI_TASK_EXECUTOR)
    public void processAiIngest(String pageId, String cleanedText, String bookId, String userId) {
        log.info(" [异步线程开启] 开始处理笔记 pageId: {}", pageId);

        int finalStatus = 2;
        StringBuilder errorMsgBuilder = new StringBuilder();

        // 1. AI 深度洗稿
        String finalArticle = textProcessManager.process(cleanedText);

        // 2. AI 生成标题
        String title;
        try {
            title = pageAssistant.generateTitle(finalArticle);
        } catch (Exception e) {
            log.warn(" [降级] AI 生成标题失败 pageId: {}, 使用截取兜底", pageId, e);
            title = finalArticle.length() > 30 ? finalArticle.substring(0, 30) : finalArticle;
            finalStatus = 4;
            errorMsgBuilder.append("Title AI failed; ");
        }

        // 3. AI 生成摘要
        String summary;
        try {
            summary = pageAssistant.generateSummary(finalArticle);
        } catch (Exception e) {
            log.warn(" [降级] AI 生成摘要失败 pageId: {}, 使用截取兜底", pageId, e);
            summary = finalArticle.length() > 200 ? finalArticle.substring(0, 200) : finalArticle;
            finalStatus = 4;
            errorMsgBuilder.append("Summary AI failed; ");
        }

        // 4. 混合分块 + 向量入库（Markdown 语义感知 + 长度兜底）
        boolean qdrantSuccess = false;
        int chunkCount = 0;
        try {
            long baseTimestamp = Instant.now().getEpochSecond();

            //核心升级：两段式分块策略
            List<TextSegment> chunks = new java.util.ArrayList<>();

            // 第一刀：按 Markdown 1~4 级标题进行结构化物理切割
            // 正则解析：(?m)开启多行模式；(?=^#{1,4} ) 使用正向前瞻，确保在切开文本的同时，保留 # 号本身
            String[] markdownSections = finalArticle.split("(?m)(?=^#{1,4} )");

            // 兜底切割器：应对某个 Markdown 章节字数严重超标的情况
            var fallbackSplitter = DocumentSplitters.recursive(CHUNK_SIZE, CHUNK_OVERLAP);

            for (String section : markdownSections) {
                if (section.trim().isEmpty()) {
                    continue;
                }
                // 第二刀：如果 section 满足长度，直接变成 1 个 Chunk；如果超长，则安全平滑地切成多个
                chunks.addAll(fallbackSplitter.split(Document.from(section.trim())));
            }

            log.info(" 笔记 pageId: {} 经 Markdown 混合切分后，共计 {} 个 Chunk", pageId, chunks.size());

            // 遍历生成的精细化 Chunks 并写入 Qdrant
            for (int i = 0; i < chunks.size(); i++) {
                String chunkText = chunks.get(i).text();
                String chunkId = pageId + "_chunk_" + i;

                // 向量化文本：拼接全局标题与当前 Markdown 段落，保证孤立的 Chunk 也拥有全局上下文
                String textToEmbed = title + "\n" + chunkText;

                Map<String, Object> payload = Map.of(
                        "userId", userId,
                        "bookId", bookId,
                        "pageId", pageId,
                        "chunkId", chunkId,
                        "chunkIndex", i,
                        "title", title,
                        "summary", summary,
                        "createdAt", baseTimestamp
                );

                qdrantTemplate.upsert(COLLECTION_NAME, chunkId, textToEmbed, chunkText, payload);
            }

            chunkCount = chunks.size();
            qdrantSuccess = true;
        } catch (Exception e) {
            log.error(" [严重错误] Qdrant 分块写入失败 pageId: {}", pageId, e);
            finalStatus = 3;
            errorMsgBuilder.append("Qdrant Upsert failed: ").append(e.getMessage());
        }

        // 5. MySQL 状态回写
        try {
            Page updatePage = new Page();
            updatePage.setId(pageId);
            updatePage.setTitle(title);
            updatePage.setSummary(summary);
            updatePage.setContent(finalArticle);

            if (qdrantSuccess) {
                updatePage.setChunkCount(chunkCount);
            } else {
                updatePage.setChunkCount(0);
            }

            updatePage.setAiProcessStatus(finalStatus);

            String finalErrorMsg = errorMsgBuilder.toString();
            updatePage.setAiProcessMsg(finalErrorMsg.length() > 200 ? finalErrorMsg.substring(0, 200) : (finalErrorMsg.isEmpty() ? "Success" : finalErrorMsg));
            updatePage.setUpdatedAt(LocalDateTime.now());

            pageMapper.updateById(updatePage);

            if (finalStatus == 2) {
                log.info(" [异步线程完美收官] 笔记 pageId: {} 成功入库（分块模式）", pageId);
            } else {
                log.info(" [异步线程降级收官] 笔记 pageId: {} 处理完成，状态: {}", pageId, finalStatus);
            }

        } catch (Exception e) {
            log.error(" [灾难级错误] MySQL 最终状态回写失败 pageId: {}", pageId, e);
        }
    }
}