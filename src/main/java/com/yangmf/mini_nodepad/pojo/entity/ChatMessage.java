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

    // ==================== Phase 4 · B1：Graphiti 写入链状态 ====================
    //
    // ⚠️ 这四个字段构成「双字段状态机 + 互斥锁」，读代码前先记住一句话：
    //    graphiti_dispatched_at IS NULL 【有且不只有】一种含义 —— 它有三种：
    //      ① 尚未投递（正常待投 / 熔断期被跳过 / scan 崩溃漏投）
    //      ② 已被 MemorySignalJudge 判否（永不投递，已写 graphiti_write_audit）
    //      ③ 毒消息 / 重试耗尽（永不投递，已写 graphiti_write_audit）
    //    → 任何把「dispatched_at IS NULL」直接当作「待投递」的 SQL 都是错的，
    //      必须与审计表联合判断（NOT EXISTS）。反过来，对账任务只允许看 IS NOT NULL，
    //      这样它连犯错的入口都没有。

    /**
     * 投递到 Graphiti 的时刻（CAS 占位成功时写入）。
     *
     * <p>★ 双重角色：既是「何时投的」审计事实，又是 CAS 占位的<b>互斥锁凭证</b>。
     * 两个角色由同一条
     * {@code UPDATE ... WHERE dispatched_at IS NULL OR dispatched_at < NOW() - INTERVAL 3 MINUTE}
     * 胜任 —— 因此不需要任何 Redis 锁参与（删掉 Redis setnx 层正是为此）。
     *
     * <p>⚠️ 「投递中」是一个<b>可超时自动释放的软锁</b>，不是永久标记：
     * 若它不可超时，就退化成 setnx 那种「重试回来的消息被自己的锁永久拒收」的死锁。
     */
    @Schema(description = "投递到 Graphiti 的时刻（CAS 占位成功时写入）")
    private LocalDateTime graphitiDispatchedAt;

    /** 确认落库到图谱的时刻。只有对账任务（GraphitiConfirmJob）能写它。 */
    @Schema(description = "确认落库到图谱的时刻（对账任务写入）")
    private LocalDateTime graphitiSyncedAt;

    /**
     * 对账未命中的累计轮次。
     *
     * <p>没有它，「窗口足够大、但那条消息就是永远落不了库」的情形会被无限重投
     * （对账任务每 5 分钟重投一次、永远重投下去）—— 上限 + 隔离是防无限重投的另一半。
     */
    @Schema(description = "对账未命中的累计轮次；达上限即隔离，防无限重投")
    private Integer graphitiConfirmRounds;

    /**
     * 确认命中时回填的服务端 episode uuid。
     *
     * <p>为什么必需：服务端 uuid <b>事前不可知</b>（202 响应不含它），而
     * 「遗忘权 {@code DELETE /episode/{uuid}}」与「记忆溯源」都需要它；
     * {@code /episodes} 无过滤、无游标，事后去列表里翻是不可靠的
     * → 在确认命中的那一次响应里顺手落库，零额外请求。
     */
    @Schema(description = "确认命中时回填的服务端 episode uuid")
    private String graphitiEpisodeUuid;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @TableLogic
    @Schema(description = "逻辑删除标记 0-正常 1-已删除")
    private Integer deleted;
}