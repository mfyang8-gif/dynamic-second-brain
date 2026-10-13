package com.yangmf.mini_nodepad.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yangmf.mini_nodepad.ai.rag.SourceReference;
import com.yangmf.mini_nodepad.json.JsonArrayTypeHandler;
import com.yangmf.mini_nodepad.json.SourceReferenceListTypeHandler;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
// 【关键修复】必须开启 autoResultMap = true，否则 JsonArrayTypeHandler 在查询时无法反序列化
@TableName(value = "chat_message", autoResultMap = true)
@Schema(name = "对话消息")
public class ChatMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_UUID)
    @Schema(description = "消息ID")
    private String id;

    @Schema(description = "所属会话ID")
    private String sessionId;

    @Schema(description = "角色: user / assistant")
    private String role;

    @Schema(description = "消息正文")
    private String content;

    @TableField(typeHandler = JsonArrayTypeHandler.class)
    @Schema(description = "引用来源PageId列表")
    private List<String> sourcePageIds;

    /**
     * 【2026-10-12 落库补齐】此前引用与推荐只存在于 SSE 事件流里，历史接口查不出来，
     * 刷新页面 / 换标签页（sessionStorage 缓存失效）后「引用来源」「猜你想问」两块消失。
     */
    @TableField(typeHandler = SourceReferenceListTypeHandler.class)
    @Schema(description = "RAG命中引用片段（assistant 消息才有）")
    private List<SourceReference> sourceReferences;

    @TableField(typeHandler = JsonArrayTypeHandler.class)
    @Schema(description = "推荐追问（assistant 消息才有，suggestions 生成后异步回写）")
    private List<String> suggestions;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @TableLogic
    @Schema(description = "逻辑删除标记 0-正常 1-已删除")
    private Integer deleted;
}