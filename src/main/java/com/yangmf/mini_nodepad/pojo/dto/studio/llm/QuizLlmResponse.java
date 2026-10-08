package com.yangmf.mini_nodepad.pojo.dto.studio.llm;

import dev.langchain4j.model.output.structured.Description;
import lombok.Data;

import java.util.List;

/**
 * 测验模块的纯净 LLM 响应 DTO。
 * 不继承 ArtifactPayload，无 @JsonTypeInfo，仅包含大模型需要生成的业务字段。
 */
@Data
public class QuizLlmResponse {

    @Description("测验题目列表")
    private List<Question> questions;

    @Data
    public static class Question {
        @Description("题目类型：CHOICE(选择题), FILL(填空题), SHORT_ANSWER(简答题)")
        private String type;

        @Description("题干内容")
        private String stem;

        @Description("选项列表，选择题必须有4个选项(A/B/C/D)，填空题和简答题为空数组")
        private List<String> options;

        @Description("正确答案，选择题填字母(如'A'或'AB')，填空题填正确内容，简答题填参考答案")
        private String answer;

        @Description("答案解析，50-100字，说明为什么这个答案是正确的")
        private String explanation;

        @Description("填空题专用：含 ___ 的完整句子，其他题型可为null")
        private String blankText;
    }
}
