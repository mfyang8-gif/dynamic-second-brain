// pojo/dto/studio/FlashcardRequest.java
package com.yangmf.mini_nodepad.pojo.dto.studio.request;

import com.yangmf.mini_nodepad.enums.studio.*;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class FlashcardRequest extends BaseStudioRequest {

    @NotNull
    private CountLevel countLevel = CountLevel.STANDARD;

    @NotNull
    private Difficulty difficulty = Difficulty.MEDIUM;

    @NotNull
    private CardFocus cardFocus = CardFocus.AUTO;
}