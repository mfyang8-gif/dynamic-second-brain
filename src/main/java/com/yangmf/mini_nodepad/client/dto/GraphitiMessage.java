package com.yangmf.mini_nodepad.client.dto;

import java.time.OffsetDateTime;

/**
 * 单条待写入消息（服务端 {@code AddMessagesRequest.messages[]} 的元素）。
 *
 * <p>字段级契约见设计文档 §2.2，以及附录 B 的陷阱 1~4、31~33。这里有三个
 * 「不写清楚就必出事」的点，全部已在构造期钉死：
 *
 * <ol>
 *   <li><b>uuid 必须为 null</b>（陷阱 31）—— 服务端 {@code uuid != null} 的语义是
 *       「update 一个<b>已存在</b>的 episode」，新建时传 uuid 会抛 {@code NodeNotFoundError}，
 *       后台 job 静默失败、什么都不写，而 <b>HTTP 早已返回 202</b> → 100% 丢数据且零告警。
 *       本 record 的紧凑构造器直接禁止非 null，从源头消除这类调用。</li>
 *   <li><b>timestamp 必须显式传</b>（陷阱 4）—— 服务端默认 {@code utc_now()}，
 *       历史对话会被盖上「处理时刻」，双时间戳裁决直接失真。</li>
 *   <li><b>role 字段必须出现在 JSON 里</b>（陷阱 2）—— 服务端 {@code role: str | None} 没有默认值，
 *       字段缺失即 422（可为 null，但字段必须出现）。因此本类<b>不</b>加
 *       {@code @JsonInclude(NON_NULL)}；构造时 null 会被归一为 {@code "user"}，
 *       既满足「字段必现」，又避免无意义的 null 语义。</li>
 * </ol>
 *
 * <p>⚠️ 一次 POST 携带 N 条 = 服务端 N 次<b>串行</b> {@code add_episode}
 * （单协程 worker，每条约 20~45s）→ 设计上按 N=1 逐条投递（§3.9）。
 */
public record GraphitiMessage(

        /** 消息正文。 */
        String content,

        /** ★★ 写入时【必须为 null】（陷阱 31）。 */
        String uuid,

        /** ★ 确认锚点载体，约定 {@code "cm_" + chatMessageId}（陷阱 33）。 */
        String name,

        /** 角色类型，必填。 */
        GraphitiRoleType roleType,

        /** ⚠️ 必填字段（可为 null，但字段必须出现）；null 会被归一为 "user"。 */
        String role,

        /** ⚠️ 必须显式传 {@code chat_message.created_at} 转成的 UTC 值。 */
        OffsetDateTime timestamp,

        /** 来源说明：写「人话式来源说明」，前端会展示给用户，禁止塞技术标记（陷阱 17）。 */
        String sourceDescription) {

    /** 确认锚点前缀。与 B1 对账任务（GraphitiConfirmJob）共用，改动必须两侧同步。 */
    public static final String CONFIRM_ANCHOR_PREFIX = "cm_";

    /** 服务端对 role 的常规取值。 */
    public static final String DEFAULT_ROLE = "user";

    public GraphitiMessage {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("GraphitiMessage.content 不能为空");
        }
        if (content.length() > MAX_CONTENT_LENGTH) {
            throw new IllegalArgumentException(
                    "GraphitiMessage.content 超长（" + content.length() + " > " + MAX_CONTENT_LENGTH
                            + "）：单条 episode 过大会拖垮服务端抽取并放大 LLM 计费，应在投递前按业务规则截断");
        }
        if (uuid != null) {
            throw new IllegalArgumentException(
                    "GraphitiMessage.uuid 必须为 null：非 null 的语义是「update 一个已存在的 episode」，"
                            + "新建传 uuid 会抛 NodeNotFoundError 并被静默丢弃（HTTP 仍返回 202）");
        }
        if (roleType == null) {
            throw new IllegalArgumentException(
                    "GraphitiMessage.roleType 必填（user / assistant / system）");
        }
        if (timestamp == null) {
            throw new IllegalArgumentException(
                    "GraphitiMessage.timestamp 必须显式传：服务端默认 utc_now() 会把历史对话盖成处理时刻，"
                            + "双时间戳裁决失效");
        }
        if (role == null || role.isBlank()) {
            role = DEFAULT_ROLE;
        }
    }

    /**
     * 单条消息正文长度上限（输入防御）。
     *
     * <p>为什么需要它：本类不设上限时，任何上游 bug（例如把整篇文档当对话投递）都会直接打到 Graphiti ——
     * 服务端每条消息要跑一次 LLM 抽取，超长正文既拖垮消费吞吐、又按 token 计费。
     * 上限取 8000 字符：足够容纳正常的长对话发言，又能在明显异常时立刻失败。
     */
    public static final int MAX_CONTENT_LENGTH = 8000;

    /**
     * 以 {@code chat_message} 主键为确认锚点，构造一条新 episode 消息（推荐入口）。
     *
     * <p>为什么锚点必须寄存在 {@code name} 上：{@code POST /messages} 的 202 响应
     * <b>不含服务端生成的 uuid</b>，而 {@code /episodes} 又无过滤、无游标 →
     * 服务端 uuid 事前不可知、事后难反查，「落库确认」根本写不出来（陷阱 33）。
     * 而 {@code name} 默认为空串、完全空闲，可写、可持久、可从 {@code /episodes} 读回。
     *
     * <p>⚠️ 参数类型是 {@code String} 而不是数值型：本工程 {@code chat_message.id}
     * 的列类型是 <b>VARCHAR</b>（{@code IdType.ASSIGN_UUID}），改用数值型会在
     * 「B0 看着对、B1 联调必炸」的位置留下一个类型错配。
     *
     * @param chatMessageId 本地 chat_message 主键（UUID 字符串），不能为空
     */
    public static GraphitiMessage forChatMessage(String chatMessageId,
                                                 String content,
                                                 GraphitiRoleType roleType,
                                                 OffsetDateTime timestamp,
                                                 String sourceDescription) {
        if (chatMessageId == null || chatMessageId.isBlank()) {
            throw new IllegalArgumentException("chatMessageId 不能为空：落库确认锚点依赖它");
        }
        return new GraphitiMessage(content, null, CONFIRM_ANCHOR_PREFIX + chatMessageId,
                roleType, DEFAULT_ROLE, timestamp, sourceDescription);
    }

    /**
     * 从 {@code name} 锚点还原 {@code chat_message.id}（对账任务使用）。
     *
     * <p>与 {@link #forChatMessage} 互为逆运算：两侧必须<b>同时</b>改动，
     * 否则对账会永远匹配不上、把所有消息都当成「投递失败」重投（暗雷 3）。
     *
     * @return 还原出的主键；{@code name} 不以锚点前缀开头时返回 {@code null}
     */
    public static String chatMessageIdOf(String name) {
        if (name == null || !name.startsWith(CONFIRM_ANCHOR_PREFIX)) {
            return null;
        }
        String id = name.substring(CONFIRM_ANCHOR_PREFIX.length());
        return id.isBlank() ? null : id;
    }
}
