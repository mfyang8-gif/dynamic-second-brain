package com.yangmf.mini_nodepad.pojo.dto.studio.request;

import com.yangmf.mini_nodepad.enums.studio.PresentationAudienceEnum;
import com.yangmf.mini_nodepad.enums.studio.PresentationLengthEnum;
import com.yangmf.mini_nodepad.enums.studio.PresentationVisualStyleEnum;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class PresentationRequest extends BaseStudioRequest {

    @NotNull
    private PresentationLengthEnum lengthLevel = PresentationLengthEnum.STANDARD;

    @NotNull
    private PresentationAudienceEnum targetAudience = PresentationAudienceEnum.GENERAL;

    @NotNull
    private PresentationVisualStyleEnum visualStyle = PresentationVisualStyleEnum.BUSINESS;
}