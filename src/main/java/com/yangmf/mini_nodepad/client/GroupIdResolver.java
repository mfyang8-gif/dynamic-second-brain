package com.yangmf.mini_nodepad.client;

import org.springframework.stereotype.Component;

/**
 * {@code group_id} 的唯一生成点（P5a / P5b）。
 *
 * <p>为什么必须收敛到一处：{@code group_id} 是 Graphiti 里<b>唯一的租户隔离键</b> ——
 * {@code /search} 返回的 fact <b>不带 group_id</b>（陷阱 9），一旦请求侧传错，
 * 客户端拿到的数据无法自证归属，泄露就已经发生了且零报错。
 * 而服务端对「空 {@code group_ids}」不做兜底（陷阱 5：漏传 = 跨租户全局检索 + HTTP 200）。
 *
 * <p>所以本类做两件事，缺一不可：
 * <ol>
 *   <li><b>格式唯一</b>：恒为 {@code user_{userId}}，不允许任何调用点自己拼字符串；</li>
 *   <li><b>合法性前置校验</b>：userId 为空或非正数直接抛错 ——
 *       宁可调用失败，也绝不放行一个「无主 group_id」（数据将无法按租户清理，且可能与其他租户撞车）。</li>
 * </ol>
 */
@Component
public class GroupIdResolver {

    /** 用户分区前缀。改它等于换租户命名空间，历史数据将不可见。 */
    public static final String USER_GROUP_PREFIX = "user_";

    /**
     * 生成用户级 group_id。
     *
     * @param userId 用户主键，必须为正整数
     * @return {@code user_{userId}}
     * @throws IllegalArgumentException userId 为 null、0 或负数
     */
    public String resolve(Long userId) {
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException(
                    "userId 必须为正整数：空 userId 会生成无主 group_id，数据既无法按租户清理，也可能与其他租户撞车");
        }
        return USER_GROUP_PREFIX + userId;
    }

    /**
     * 把库中的<b>字符串</b>用户主键严格解析为数值型（B1 新增）。
     *
     * <p>为什么需要这一步：本工程 {@code book.user_id} 的列类型是 <b>VARCHAR</b>
     * （如实读 DDL 所得），而 {@code group_id} 的格式是 {@code user_{数值}} ——
     * 两者必须显式转换，且转换失败<b>不能静默</b>。
     *
     * <p>★ 转换失败时返回 {@code null} 而不是抛异常：调用方（MQ 消费者 / 定时任务）
     * 需要把「数据完整性损坏」与「网络抖动」区分开 —— 前者必须<b>隔离为终态</b>，
     * 后者才该重试。若这里抛异常并被上层当成可重试异常，那条坏数据会被无限重投。
     *
     * @param raw 数据库里的字符串用户主键
     * @return 数值型用户主键；{@code raw} 为空 / 非数字 / 非正数时返回 {@code null}
     */
    public static Long parseUserId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            long value = Long.parseLong(raw.trim());
            return value > 0 ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
