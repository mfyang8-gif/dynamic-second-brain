package com.yangmf.mini_nodepad.service;

import com.yangmf.mini_nodepad.aiservice.PageAssistant;
import com.yangmf.mini_nodepad.config.ThreadPoolConfig;
import com.yangmf.mini_nodepad.enums.AiProcessStatusEnum;
import com.yangmf.mini_nodepad.mapper.PageMapper;
import com.yangmf.mini_nodepad.pojo.entity.Page;
import com.yangmf.mini_nodepad.utils.QdrantTemplate;
import com.yangmf.mini_nodepad.utils.TextProcessManager;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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

    @Value("${ai.chunk.size:500}")
    private int chunkSize;

    @Value("${ai.chunk.overlap:50}")
    private int chunkOverlap;

    @Async(ThreadPoolConfig.AI_TASK_EXECUTOR)
    public void processAiIngest(String pageId, String cleanedText, String bookId, String userId) {
        log.info("AI 异步处理开始 | pageId={}, userId={}", pageId, userId);

        AiProcessStatusEnum finalStatus = AiProcessStatusEnum.SUCCESS;
        StringBuilder errorMsgBuilder = new StringBuilder();
        String title = null;
        String summary = null;
        String finalArticle = null;

        try {
            try {
                finalArticle = textProcessManager.process(cleanedText);
                log.debug("文本清洗完成 | pageId={}, originalLength={}, cleanedLength={}",
                        pageId, cleanedText.length(), finalArticle.length());
            } catch (Exception e) {
                log.error("文本清洗失败 | pageId={}", pageId, e);
                finalStatus = AiProcessStatusEnum.FAILED;
                errorMsgBuilder.append("Text cleaning failed; ");
                finalArticle = cleanedText;
            }

            if (finalArticle == null || finalArticle.trim().isEmpty()) {
                log.warn("清洗后文本为空 | pageId={}", pageId);
                finalStatus = AiProcessStatusEnum.FAILED;
                errorMsgBuilder.append("Cleaned text is empty; ");
                finalArticle = cleanedText;
            }

            try {
                title = pageAssistant.generateTitle(finalArticle);
                log.debug("AI 标题生成成功 | pageId={}, title={}", pageId, title);
            } catch (Exception e) {
                log.warn("AI 标题生成失败，使用截取兜底 | pageId={}", pageId);
                title = finalArticle.length() > 30 ? finalArticle.substring(0, 30).trim() : finalArticle;
                finalStatus = AiProcessStatusEnum.DEGRADED;
                errorMsgBuilder.append("Title AI failed; ");
            }

            try {
                summary = pageAssistant.generateSummary(finalArticle);
                log.debug("AI 摘要生成成功 | pageId={}, summaryLength={}", pageId, summary.length());
            } catch (Exception e) {
                log.warn("AI 摘要生成失败，使用截取兜底 | pageId={}", pageId);
                summary = finalArticle.length() > 200 ? finalArticle.substring(0, 200).trim() : finalArticle;
                finalStatus = AiProcessStatusEnum.DEGRADED;
                errorMsgBuilder.append("Summary AI failed; ");
            }

            boolean qdrantSuccess = false;
            int chunkCount = 0;
            try {
                long baseTimestamp = Instant.now().getEpochSecond();

                List<TextSegment> chunks = splitByMarkdown(finalArticle);

                log.info("Markdown 混合切分完成 | pageId={}, chunkCount={}", pageId, chunks.size());

                for (int i = 0; i < chunks.size(); i++) {
                    String chunkText = chunks.get(i).text();
                    String chunkId = pageId + "_chunk_" + i;

                    String textToEmbed = (title != null ? title : "") + "\n" + chunkText;

                    Map<String, Object> payload = Map.of(
                            "userId", userId,
                            "bookId", bookId,
                            "pageId", pageId,
                            "chunkId", chunkId,
                            "chunkIndex", i,
                            "title", title != null ? title : "",
                            "summary", summary != null ? summary : "",
                            "createdAt", baseTimestamp
                    );

                    qdrantTemplate.upsert(COLLECTION_NAME, chunkId, textToEmbed, chunkText, payload);
                }

                chunkCount = chunks.size();
                qdrantSuccess = true;
                log.info("Qdrant 向量写入成功 | pageId={}, chunks={}", pageId, chunkCount);
            } catch (Exception e) {
                log.error("Qdrant 分块写入失败 | pageId={}", pageId, e);
                finalStatus = AiProcessStatusEnum.FAILED;
                errorMsgBuilder.append("Qdrant Upsert failed: ").append(e.getMessage()).append("; ");
            }

            updatePageStatus(pageId, title, summary, finalArticle, chunkCount, qdrantSuccess, finalStatus, errorMsgBuilder.toString());

        } catch (Exception e) {
            log.error("AI 异步处理未知异常 | pageId={}", pageId, e);
            try {
                updatePageStatus(pageId, title, summary, finalArticle, 0, false, AiProcessStatusEnum.FAILED,
                        "Unexpected error: " + e.getMessage());
            } catch (Exception ex) {
                log.error("异常状态回写失败 | pageId={}", pageId, ex);
            }
        }
    }

    private List<TextSegment> splitByMarkdown(String text) {
        List<TextSegment> chunks = new java.util.ArrayList<>();

        String[] markdownSections = text.split("(?m)(?=^#{1,4} )");

        var fallbackSplitter = DocumentSplitters.recursive(chunkSize, chunkOverlap);

        for (String section : markdownSections) {
            if (section.trim().isEmpty()) {
                continue;
            }
            chunks.addAll(fallbackSplitter.split(Document.from(section.trim())));
        }

        return chunks;
    }

    private void updatePageStatus(String pageId, String title, String summary,
                                  String content, int chunkCount, boolean qdrantSuccess,
                                  AiProcessStatusEnum status, String errorMsg) {
        try {
            Page updatePage = new Page();
            updatePage.setId(pageId);
            updatePage.setTitle(title);
            updatePage.setSummary(summary);
            updatePage.setContent(content);
            updatePage.setChunkCount(qdrantSuccess ? chunkCount : 0);
            updatePage.setAiProcessStatus(status);

            String finalMsg = errorMsg != null && !errorMsg.isEmpty() ? errorMsg : "Success";
            updatePage.setAiProcessMsg(finalMsg.length() > 200 ? finalMsg.substring(0, 200) : finalMsg);
            updatePage.setUpdatedAt(LocalDateTime.now());

            pageMapper.updateById(updatePage);

            if (status == AiProcessStatusEnum.SUCCESS) {
                log.info("AI 处理成功 | pageId={}, chunks={}", pageId, chunkCount);
            } else {
                log.warn("AI 处理降级完成 | pageId={}, status={}, msg={}", pageId, status.name(), finalMsg);
            }
        } catch (Exception e) {
            log.error("MySQL 状态回写失败 | pageId={}", pageId, e);
            throw new RuntimeException("MySQL 状态回写失败", e);
        }
    }
}