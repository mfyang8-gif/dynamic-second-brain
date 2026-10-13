package com.yangmf.mini_nodepad.pojo.dto;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Graphiti 写入链的行级投影（B1）。
 *
 * <p>三个查询共用它，未涉及的列保持 null：
 * <ul>
 *   <li><b>扫描窗口</b>（{@code selectScanWindow}）：messageId / sessionId / userId / content / createdAt</li>
 *   <li><b>对账目标</b>（{@code selectConfirmTargets}）：messageId / sessionId / userId / confirmRounds</li>
 *   <li><b>补投目标</b>（{@code selectReplayTargets}）：messageId / sessionId / userId / content / createdAt</li>
 * </ul>
 *
 * <p>⚠️ 为什么用可变 POJO 而不是 record：MyBatis 的自动映射对 record 需要
 * {@code argNameBasedConstructorAutoMapping}（易受编译参数影响而静默失效 ——
 * 表现为「查出来全 null」，不报错），可变 POJO 走 setter 映射最稳。
 *
 * <p>★ {@code userId} 是<b>字符串</b>：库中 {@code book.user_id} 就是 VARCHAR。
 * 转数值型只发生在构造 {@code GraphitiMessage} 的那一刻，且必须防御性解析
 * （非数字视为数据完整性问题，隔离而不是重试 —— 否则会无限重试）。</p>
 */
@Data
public class GraphitiPendingMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    /** {@code chat_message.id}（VARCHAR，同时也是确认锚点 "cm_" + id 的来源）。 */
    private String messageId;

    private String sessionId;

    /** {@code book.user_id}（VARCHAR，与实体同型）。 */
    private String userId;

    /** 消息正文（扫描窗口 / 补投需要；对账不需要）。 */
    private String content;

    /** 业务发生时刻 —— 必须原样作为 Graphiti 的 {@code timestamp}（陷阱 4）。 */
    private LocalDateTime createdAt;

    /** 对账未命中累计轮次（仅对账查询返回）。 */
    private Integer confirmRounds;
}
