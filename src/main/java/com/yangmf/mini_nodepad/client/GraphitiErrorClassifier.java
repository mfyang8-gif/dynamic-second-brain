package com.yangmf.mini_nodepad.client;

import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Graphiti 调用失败分类器（Phase 4 · B1，铁律 3）。
 *
 * <p>本类只回答一个问题：<b>这次失败该重试、该长退避、还是该判死？</b>
 * 而它的存在本身，就是为了阻止一种写法：「{@code status / 100 == 4} 一律当毒消息」。
 * 按那种写法，<b>429（限流）会被当成载荷错误直接丢进 DLQ</b> ——
 * 而 Graphiti 后面挂的是 DashScope，限流在晚高峰是常态，
 * 后果不是「延迟」而是用户的新偏好<b>成批地、永久地</b>消失（§3.7.1 称之为业务级灾难）。
 *
 * <p>判定顺序（不可调换）：<b>先看码，再看 body 关键词</b>。
 *
 * <table border="1">
 *   <caption>分类规则</caption>
 *   <tr><th>输入</th><th>分类</th><th>动作</th></tr>
 *   <tr><td>{@code 429}</td><td>RATE_LIMITED</td><td>回滚占位 + 跳过 30s，直接进 2min / 10min 档</td></tr>
 *   <tr><td>{@code 403}</td><td>RATE_LIMITED</td><td>同上（见下方说明）</td></tr>
 *   <tr><td>任意 4xx 且 body 含 {@code rate limit / throttling / quota / exceeded}</td>
 *       <td>RATE_LIMITED</td><td>同上（兜住「被包装过的限流」）</td></tr>
 *   <tr><td>{@code 400 / 422} 等其余 4xx</td><td>TOXIC</td><td>保留占位 + 进 DLQ + 写审计表（终态）</td></tr>
 *   <tr><td>{@code 5xx} / 网络层异常（无状态码）</td><td>RETRYABLE</td><td>回滚占位 + 按 attempt 进 30s / 2min / 10min</td></tr>
 * </table>
 *
 * <p>★ <b>403 为什么无条件归入限流</b>：Graphiti 是<b>内网私有服务、没有鉴权层</b>
 * （见设计文档附录 E.1），因此它透传出来的 403 在语义上只可能是上游配额问题，
 * 不可能是「权限不足」。设计文档 §3.7.1 的「显式拦截 Graphiti 透传出来的 429 / 403」
 * 一句本身也没有带关键词前提。此外两种误判的代价极不对称：
 * 把限流误判成毒消息 = 永久丢数据；把真毒消息误判成可重试 = 白重试三次后进
 * DLQ + 审计表（仍是终态，且可人工恢复）。<b>因此歧义一律倒向「可重试」。</b>
 *
 * <p>★ <b>5xx 一律可重试</b>：Graphiti 可能把 DashScope 的 429 包装成 500
 * （内部异常 → FastAPI 默认 500），所以 5xx 不需要额外识别关键词 ——
 * 「5xx 可重试」这条已经兜住了它。
 *
 * <p>⚠️ 本类<b>不</b>依赖 Spring，纯静态判定，便于离线单测（这条链路的分类逻辑一旦回归，
 * 后果是静默的数据丢失，必须有测试盯着）。
 */
@Slf4j
public final class GraphitiErrorClassifier {

    /** 重试次数上限：30s / 2min / 10min 三档走完即耗尽。 */
    public static final int MAX_ATTEMPT = 3;

    /**
     * 限流/配额判据关键词（小写匹配）。
     *
     * <p>为什么不能只看状态码：上游把限流包装成别的 4xx（甚至 500）时，
     * body 里的这些词是唯一线索。
     */
    private static final List<String> RATE_LIMIT_KEYWORDS = List.of(
            "rate limit", "ratelimit", "rate_limit",
            "throttl",
            "quota",
            "exceeded",
            "too many requests");

    private GraphitiErrorClassifier() {
    }

    /** 失败分类 —— 决定退避曲线，是重试链路的唯一判据来源。 */
    public enum Kind {
        /** 限流 / 配额耗尽：可重试，但必须走最长退避（跳过 30s 档，避开持续撞墙）。 */
        RATE_LIMITED,
        /** 可重试：连接拒绝、超时、5xx。按 attempt 递增退避。 */
        RETRYABLE,
        /** 毒消息：载荷错误，重试无意义。保留占位 + DLQ + 审计终态。 */
        TOXIC
    }

    /**
     * 分类一次调用失败。
     *
     * @param error 契约层抛出的统一异常；{@code null} 视为「未知可重试」（保守）
     */
    public static Kind classify(GraphitiClientException error) {
        if (error == null) {
            return Kind.RETRYABLE;
        }
        Integer status = error.httpStatus();

        // ① 网络层异常（连接拒绝 / 读超时）：没有状态码 → 可重试
        if (status == null) {
            return Kind.RETRYABLE;
        }

        // ② 显式限流：429 与 403 一律走最长退避（见类注释的「不对称代价」论证）
        if (status == 429 || status == 403) {
            return Kind.RATE_LIMITED;
        }

        // ③ 其余 4xx：必须先看 body 关键词，防止「被包装过的限流」被判死
        if (status >= 400 && status < 500) {
            if (containsRateLimitKeyword(error.responseBody())) {
                log.warn("4xx 响应体命中限流关键词，按可重试处理而非毒消息 | status={} | body={}",
                        status, snippet(error.responseBody()));
                return Kind.RATE_LIMITED;
            }
            return Kind.TOXIC;
        }

        // ④ 5xx / 3xx / 其它：可重试（含「DashScope 429 被包装成 500」这一情形）
        return Kind.RETRYABLE;
    }

    /** 是否可重试（含限流；限流只是退避档位不同，不是不可重试）。 */
    public static boolean isRetryable(GraphitiClientException error) {
        return classify(error) != Kind.TOXIC;
    }

    /** 是否毒消息（载荷错误，永不重试）。 */
    public static boolean isToxic(GraphitiClientException error) {
        return classify(error) == Kind.TOXIC;
    }

    /** 是否限流 / 配额耗尽（决定「跳过 30s 档」）。 */
    public static boolean isRateLimited(GraphitiClientException error) {
        return classify(error) == Kind.RATE_LIMITED;
    }

    /**
     * 按 attempt 计算退避档位（0=30s，1=2min，2=10min）。
     *
     * <p>★ 限流强制跳过 30s 档：持续撞墙对限流毫无帮助，反而把配额窗口填满
     * （§3.7.1 补丁 4）。
     *
     * @param attempt 已尝试次数（从 0 开始）
     * @param kind    本次失败分类
     * @return 退避档位序号，调用方据此选择重试队列
     */
    public static int backoffLevel(int attempt, Kind kind) {
        int level = Math.max(0, attempt);
        if (kind == Kind.RATE_LIMITED) {
            level = Math.max(level, 1);
        }
        return Math.min(level, MAX_ATTEMPT - 1);
    }

    /** 是否已达重试上限（应进 DLQ + 审计终态）。 */
    public static boolean isExhausted(int attempt) {
        return attempt >= MAX_ATTEMPT;
    }

    private static boolean containsRateLimitKeyword(String body) {
        if (body == null || body.isBlank()) {
            return false;
        }
        String lower = body.toLowerCase(Locale.ROOT);
        for (String keyword : RATE_LIMIT_KEYWORDS) {
            if (lower.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private static String snippet(String body) {
        if (body == null) {
            return "";
        }
        String trimmed = body.strip();
        return trimmed.length() <= 120 ? trimmed : trimmed.substring(0, 120) + "...";
    }

    /** 供调用方判断「是否同一类失败」时使用（避免字符串比较）。 */
    public static boolean isSameKind(Kind a, Kind b) {
        return Objects.equals(a, b);
    }
}
