package com.yangmf.mini_nodepad.pojo.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.yangmf.mini_nodepad.ai.rag.SourceReference;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 会话历史消息的列表展示 VO。
 *
 * 与实体 ChatMessage 的差异：
 *   - 不外露 deleted（逻辑删除标记是内部实现细节）；
 *   - 时间统一格式化为 yyyy-MM-dd HH:mm:ss，前端不再各自 parse。
 * 沿用 StudioArtifactListVO 的「出入参模型分离」纪律：
 * 前端永远不直接消费 Entity。
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Schema(name = "会话历史消息VO")
public class ChatMessageVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "消息ID")
    private String id;

    @Schema(description = "角色: user / assistant")
    private String role;

    @Schema(description = "消息正文")
    private String content;

    @Schema(description = "引用来源PageId列表（assistant 消息才有，可能为空）")
    private List<String> sourcePageIds;

    @Schema(description = "RAG命中引用片段（assistant 消息才有；历史回放「引用来源」块的数据源）")
    private List<SourceReference> sourceReferences;

    @Schema(description = "推荐追问（assistant 消息才有；历史回放「猜你想问」块的数据源）")
    private List<String> suggestions;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @Schema(description = "创建时间")
    private LocalDateTime createdAt;
}
