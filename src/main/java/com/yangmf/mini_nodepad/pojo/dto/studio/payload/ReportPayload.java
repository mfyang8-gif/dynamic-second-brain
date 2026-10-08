package com.yangmf.mini_nodepad.pojo.dto.studio.payload;

import com.yangmf.mini_nodepad.enums.studio.ArtifactType;
import com.yangmf.mini_nodepad.enums.studio.ReportFormatEnum;
import com.yangmf.mini_nodepad.enums.studio.WidgetStatusEnum;
import dev.langchain4j.model.output.structured.Description;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
public class ReportPayload extends ArtifactPayload {

    public ReportPayload() {
        super(ArtifactType.REPORT);
    }

    @Description("报告输出形态：DOCUMENT 或 INTERACTIVE")
    private ReportFormatEnum format;

    @Description("完整的 Markdown 报告正文")
    private String markdown;

    @Description("报告文件的 OSS 下载链接（仅 DOCUMENT 模式或导出时有值）")
    private String fileUrl;

    @Description("互动组件状态字典，Key 为 widgetId（仅 INTERACTIVE 模式有意义）")
    private Map<String, WidgetState> widgetStates;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WidgetState {
        private WidgetStatusEnum status;
        private String targetArtifactId;
    }
}