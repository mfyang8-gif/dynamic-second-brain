package com.yangmf.mini_nodepad.service;

import com.yangmf.mini_nodepad.enums.studio.ArtifactType;
import com.yangmf.mini_nodepad.pojo.dto.studio.request.BaseStudioRequest;
import com.yangmf.mini_nodepad.pojo.dto.studio.request.UpdateWidgetStateReq;
import com.yangmf.mini_nodepad.pojo.entity.StudioArtifact;

import java.util.List;

public interface StudioService {

    String submit(BaseStudioRequest request, ArtifactType type);

    StudioArtifact getById(String artifactId);

    List<StudioArtifact> listByBookId(String bookId);

    List<StudioArtifact> listByBookIdAndType(String bookId, ArtifactType type);

    void deleteById(String artifactId);

    void updateWidgetState(String reportArtifactId, String widgetId, UpdateWidgetStateReq req);

    String exportReportAsDocx(String reportArtifactId);
}