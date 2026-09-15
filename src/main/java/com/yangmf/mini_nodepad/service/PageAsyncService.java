package com.yangmf.mini_nodepad.service;

import com.yangmf.mini_nodepad.aiservice.PageAssistant;
import com.yangmf.mini_nodepad.config.ThreadPoolConfig;
import com.yangmf.mini_nodepad.mapper.PageMapper;
import com.yangmf.mini_nodepad.pojo.entity.Page;
import com.yangmf.mini_nodepad.utils.QdrantTemplate;
import com.yangmf.mini_nodepad.utils.TextProcessManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class PageAsyncService {

    private final TextProcessManager textProcessManager;
    private final PageAssistant pageAssistant;
    private final QdrantTemplate qdrantTemplate;
    private final PageMapper pageMapper;

    // 💡 规范 1：消除魔法字符串
    private static final String COLLECTION_NAME = "dynamic_brain";

    /**
     * 核心异步流水线
     *
     * @param cleanedText 注意：这里传入的已经是经过 physicalClean 的干净文本
     */
    @Async(ThreadPoolConfig.AI_TASK_EXECUTOR)
    public void processAiIngest(String pageId, String cleanedText, String bookId, String userId) {
        log.info(" [异步线程开启] 开始处理笔记 pageId: {}", pageId);

        // 用于记录最终流转状态（默认 2-成功。如果中间有降级，可能会被改为 4-部分降级 或 3-失败）
        int finalStatus = 2;
        StringBuilder errorMsgBuilder = new StringBuilder();

        // 1. AI 深度洗稿 (TextProcessManager 内部已做兜底，不会抛出异常)
        String finalArticle = textProcessManager.process(cleanedText);

        // 2. 细粒度降级：AI 生成标题
        String title;
        try {
            title = pageAssistant.generateTitle(finalArticle);
        } catch (Exception e) {
            log.warn(" [降级] AI 生成标题失败 pageId: {}, 使用截取兜底", pageId, e);
            title = finalArticle.length() > 30 ? finalArticle.substring(0, 30) : finalArticle;
            finalStatus = 4; // 标记为部分降级
            errorMsgBuilder.append("Title AI failed; ");
        }

        // 3. 细粒度降级：AI 生成摘要
        String summary;
        try {
            summary = pageAssistant.generateSummary(finalArticle);
        } catch (Exception e) {
            log.warn(" [降级] AI 生成摘要失败 pageId: {}, 使用截取兜底", pageId, e);
            summary = finalArticle.length() > 200 ? finalArticle.substring(0, 200) : finalArticle;
            finalStatus = 4; // 标记为部分降级
            errorMsgBuilder.append("Summary AI failed; ");
        }

        // 4. Qdrant 向量入库 (独立 try-catch，绝对不能阻断后续 MySQL 更新)
        boolean qdrantSuccess = false;
        try {
            String textToEmbed = title + "\n" + summary;
            Map<String, Object> payload = Map.of(
                    "userId", userId,
                    "bookId", bookId,
                    "title", title,
                    "summary", summary,
                    "createdAt", Instant.now().getEpochSecond() // 💡 规范 2：标准时间戳
            );
            qdrantTemplate.upsert(COLLECTION_NAME, pageId, textToEmbed, payload);
            qdrantSuccess = true;
        } catch (Exception e) {
            log.error(" [严重错误] Qdrant 写入失败 pageId: {}", pageId, e);
            finalStatus = 3; // 向量没存进去，语义搜索必然搜不到，必须标记为严重失败
            errorMsgBuilder.append("Qdrant Upsert failed: ").append(e.getMessage());
        }

        // 5. 终极防线：无论前面发生什么，MySQL 的状态必须被正确回写！
        try {
            Page updatePage = new Page();
            updatePage.setId(pageId);
            updatePage.setTitle(title);
            updatePage.setSummary(summary);
            updatePage.setContent(finalArticle);

            // 只有 Qdrant 写入成功，才绑定 pointId
            if (qdrantSuccess) {
                updatePage.setQdrantPointId(pageId);
            }

            updatePage.setAiProcessStatus(finalStatus);

            // 截断错误信息，防止超长报错
            String finalErrorMsg = errorMsgBuilder.toString();
            updatePage.setAiProcessMsg(finalErrorMsg.length() > 200 ? finalErrorMsg.substring(0, 200) : (finalErrorMsg.isEmpty() ? "Success" : finalErrorMsg));
            updatePage.setUpdatedAt(LocalDateTime.now());

            pageMapper.updateById(updatePage);

            if (finalStatus == 2) {
                log.info(" [异步线程完美收官] 笔记 pageId: {} 成功入库", pageId);
            } else {
                log.info(" [异步线程降级收官] 笔记 pageId: {} 处理完成，状态: {}", pageId, finalStatus);
            }

        } catch (Exception e) {
            // 这属于极其罕见的数据库宕机或网络断开
            log.error(" [灾难级错误] MySQL 最终状态回写失败 pageId: {}", pageId, e);
        }
    }
}