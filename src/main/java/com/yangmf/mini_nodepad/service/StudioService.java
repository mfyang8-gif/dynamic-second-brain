package com.yangmf.mini_nodepad.service;

import com.yangmf.mini_nodepad.enums.studio.ArtifactType;
import com.yangmf.mini_nodepad.pojo.dto.studio.query.StudioHistoryQueryDTO;
import com.yangmf.mini_nodepad.pojo.dto.studio.request.BaseStudioRequest;
import com.yangmf.mini_nodepad.pojo.dto.studio.request.UpdateWidgetStateReq;
import com.yangmf.mini_nodepad.pojo.entity.StudioArtifact;
import com.yangmf.mini_nodepad.pojo.vo.StudioArtifactDetailVO;
import com.yangmf.mini_nodepad.pojo.vo.StudioArtifactListVO;
import com.yangmf.mini_nodepad.result.PageResult;

import java.util.List;

public interface StudioService {

    String submit(BaseStudioRequest request, ArtifactType type);

    StudioArtifact getById(String artifactId);

    List<StudioArtifact> listByBookId(String bookId);

    List<StudioArtifact> listByBookIdAndType(String bookId, ArtifactType type);

    /**
     * 生成历史分页查询（列表）。
     * 安全契约：
     * · userId 只从 BaseContext.getCurrentId() 取，方法签名里根本没有 userId 入参；
     * · 强制分页（MyBatis-Plus Page + LIMIT），单页上限 100；
     * · SQL 层 .select(...) 剥离 payload 大字段；
     * · 返回列表 VO，不暴露实体。
     */
    PageResult<StudioArtifactListVO> pageHistory(StudioHistoryQueryDTO queryDTO);

    /**
     * 生成历史详情（含 payload 大字段，仅此接口放行）。
     */
    StudioArtifactDetailVO getDetail(String artifactId);

    void deleteById(String artifactId);

    void updateWidgetState(String reportArtifactId, String widgetId, UpdateWidgetStateReq req);

    String exportReportAsDocx(String reportArtifactId);
}