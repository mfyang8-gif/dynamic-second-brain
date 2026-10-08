// pojo/dto/studio/QuizRequest.java
package com.yangmf.mini_nodepad.pojo.dto.studio.request;

import com.yangmf.mini_nodepad.enums.studio.*;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)

public class QuizRequest extends BaseStudioRequest {

    @NotNull
    private CountLevel countLevel = CountLevel.STANDARD;

    @NotNull
    private Difficulty difficulty = Difficulty.MEDIUM;

    private List<QuestionType> questionTypes;

    @NotNull
    private QuizFocus quizFocus = QuizFocus.CONCEPTUAL;
}