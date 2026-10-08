package com.yangmf.mini_nodepad.pojo.dto.studio.payload;

import com.yangmf.mini_nodepad.enums.studio.ArtifactType;
import com.yangmf.mini_nodepad.pojo.dto.studio.llm.QuizLlmResponse;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 测验工件持久化负载。
 * 复用 QuizLlmResponse.Question 作为题目结构，自身仅负责 artifactType 标识与 DB 存储。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class QuizPayload extends ArtifactPayload {

    public QuizPayload() {
        super(ArtifactType.QUIZ);
    }

    private List<QuizLlmResponse.Question> questions;
}