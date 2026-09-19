package com.yangmf.mini_nodepad.service;

import com.yangmf.mini_nodepad.pojo.dto.PageIngestDTO;
import com.yangmf.mini_nodepad.pojo.dto.PageQueryDTO;
import com.yangmf.mini_nodepad.pojo.vo.PageVO;
import com.yangmf.mini_nodepad.result.PageResult;

import java.util.List;

public interface PageService {

    void ingestPage(PageIngestDTO dto);

    PageVO getPageById(String id);

    PageResult<PageVO> listPagesByBookId(String bookId, PageQueryDTO queryDTO);

    void softDeletePage(String id);

    void softDeletePages(List<String> ids);

    void permanentDeletePage(String id);

    void permanentDeletePages(List<String> ids);

    void restorePage(String id);

    void restorePages(List<String> ids);

    PageResult<PageVO> listRecycleBin(PageQueryDTO queryDTO);

    void emptyRecycleBin();

    String generateTitlePreview(String content);

    String generateSummaryPreview(String content);

    void retryAiProcess(String id);

    void rebuildBm25Index();
}