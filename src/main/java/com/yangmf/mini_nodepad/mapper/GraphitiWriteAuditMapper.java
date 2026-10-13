package com.yangmf.mini_nodepad.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yangmf.mini_nodepad.pojo.entity.GraphitiWriteAudit;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * Graphiti 写入终态审计 Mapper（B1）。
 *
 * <p>只有两类操作：<b>幂等写入</b>与<b>存在性判断</b>。
 * 存在性判断是 Confirm / Replay 两条 SQL 的 {@code NOT EXISTS} 子查询之核心 ——
 * 它决定了「这条消息是否已进入终态、永不再投」。
 */
@Mapper
public interface GraphitiWriteAuditMapper extends BaseMapper<GraphitiWriteAudit> {

    /**
     * 幂等写入一条终态隔离记录。
     *
     * <p>★ 必须是 {@code INSERT IGNORE}：MQ 是 at-least-once，同一条消息可能被重复消费，
     * 而 {@code message_id} 上有 UNIQUE 约束 —— 用普通 INSERT 会在重复隔离时抛
     * {@code DuplicateKeyException}，把「已经正确的终态」变成一次消费失败。
     *
     * @return 影响行数：1 = 新隔离；0 = 早已隔离（幂等命中，非异常）
     */
    @Insert("""
            INSERT IGNORE INTO graphiti_write_audit
                (user_id, message_id, reason, content_snapshot, detail, created_at)
            VALUES
                (#{userId}, #{messageId}, #{reason}, #{contentSnapshot}, #{detail}, NOW())
            """)
    int insertIgnore(@Param("userId") String userId,
                     @Param("messageId") String messageId,
                     @Param("reason") String reason,
                     @Param("contentSnapshot") String contentSnapshot,
                     @Param("detail") String detail);

    /**
     * 该消息是否已进入终态（永不投递）。
     *
     * <p>调用点语义说明：
     * <ul>
     *   <li>投递层在「判定毒消息」之前先查一次 —— 已隔离的直接 ack 丢弃，
     *       不 POST、不进 DLQ（暗雷 1 的第三道防线）；</li>
     *   <li>扫描层在投递前复核 —— 判否 / 已隔离的消息永不被（重）投。</li>
     * </ul>
     */
    @Select("SELECT COUNT(1) FROM graphiti_write_audit WHERE message_id = #{messageId}")
    int existsByMessageId(@Param("messageId") String messageId);
}
