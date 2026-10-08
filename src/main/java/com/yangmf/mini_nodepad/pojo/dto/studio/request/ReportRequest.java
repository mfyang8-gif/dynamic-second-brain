package com.yangmf.mini_nodepad.pojo.dto.studio.request;

import com.yangmf.mini_nodepad.enums.studio.ReportFormatEnum;
import com.yangmf.mini_nodepad.enums.studio.TemplateFocus;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class ReportRequest extends BaseStudioRequest {

    @NotNull
    private ReportFormatEnum format = ReportFormatEnum.DOCUMENT;

    @NotNull
    private TemplateFocus templateFocus = TemplateFocus.STUDY_OVERVIEW;
}