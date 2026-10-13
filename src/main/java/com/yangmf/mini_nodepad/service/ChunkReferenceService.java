package com.yangmf.mini_nodepad.service;

import com.yangmf.mini_nodepad.pojo.vo.ChunkContextVO;

public interface ChunkReferenceService {

    /**
     * 引用原文上下文：归属校验走 page → book 链路（不存在/越权统一 403 防探测），
     * 分块内容从 Qdrant 按确定性 chunkId（{pageId}_chunk_{index}）直取，
     * 返回当前块全文 + 前后块 100 字预览。
     */
    ChunkContextVO getChunkContext(String pageId, Integer chunkIndex);
}
