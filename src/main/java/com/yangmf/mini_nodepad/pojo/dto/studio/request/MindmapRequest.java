// pojo/dto/studio/MindmapRequest.java
package com.yangmf.mini_nodepad.pojo.dto.studio.request;

import com.yangmf.mini_nodepad.enums.studio.*;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class MindmapRequest extends BaseStudioRequest {

    @NotNull
    private DepthLevel depthLevel = DepthLevel.STANDARD;

    @NotNull
    private StructureType structureType = StructureType.AUTO;
}