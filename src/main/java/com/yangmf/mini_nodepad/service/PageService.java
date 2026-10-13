package com.yangmf.mini_nodepad.service;

import com.yangmf.mini_nodepad.pojo.dto.PageIngestDTO;
import com.yangmf.mini_nodepad.pojo.dto.PageQueryDTO;
import com.yangmf.mini_nodepad.pojo.dto.PageRenameDTO;
import com.yangmf.mini_nodepad.pojo.dto.WebBatchImportRequest;
import com.yangmf.mini_nodepad.pojo.vo.PageVO;
import com.yangmf.mini_nodepad.result.BatchOperationResult;
import com.yangmf.mini_nodepad.result.PageResult;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface PageService {

    String ingestPage(PageIngestDTO dto);

    PageVO getPageById(String id);
    PageVO renamePage(String id, PageRenameDTO dto);

    PageResult<PageVO> listPagesByBookId(String bookId, PageQueryDTO queryDTO);

    void softDeletePage(String id);

    BatchOperationResult softDeletePages(List<String> ids);

    void permanentDeletePage(String id);

    BatchOperationResult permanentDeletePages(List<String> ids);

    void restorePage(String id);

    BatchOperationResult restorePages(List<String> ids);

    PageResult<PageVO> listRecycleBin(PageQueryDTO queryDTO);

    void emptyRecycleBin();

    String generateTitlePreview(String content);

    String generateSummaryPreview(String content);

    void retryAiProcess(String id);

    void rebuildBm25Index();


    /** 文件解析入库：安全校验 + 全文提取（不截断）+ 复用异步入库链路，返回 pageId */
    String ingestFile(MultipartFile file, String bookId, Integer autoOptimize, Integer allowAiModify);

    /** 联网搜索勾选结果批量导入（sourceType=WEB，不截断），返回成功新建的 pageId 列表 */
    List<String> ingestWebBatch(WebBatchImportRequest request);
}