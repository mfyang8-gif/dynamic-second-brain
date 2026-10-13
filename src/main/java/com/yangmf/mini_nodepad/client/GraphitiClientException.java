package com.yangmf.mini_nodepad.client;

/**
 * Graphiti 调用异常 —— 契约层的统一失败出口。
 *
 * <p>设计意图：把「HTTP 状态码 + 响应体片段」原样带出到调用方，
 * 供 B1 的 {@code GraphitiErrorClassifier} 做重试分类。B0 阶段不实现分类逻辑，
 * 只保证分类所需的原始信息不丢失。
 *
 * <p>★ 为什么单独立一个异常类，而不是直接抛 Spring 的 {@code RestClientResponseException}：
 * 一旦上层直接依赖 Spring 的 HTTP 异常类型，「先看码、再看 body 关键词」的分类规则就无处安放，
 * 极易退化成 {@code status / 100 == 4} 这种一刀切 —— 而按那个规则，
 * <b>429（限流）会被当成「4xx 毒消息」直接进 DLQ</b>，高峰期的新偏好就成批永久丢了（陷阱 22 / 铁律 3）。
 *
 * <p>⚠️ 不打印完整响应体：Graphiti 的错误页可能很长，异常信息里只保留截断片段，
 * 避免污染日志与回显。
 */
public class GraphitiClientException extends RuntimeException {

    /** 异常消息里保留的响应体长度上限。 */
    private static final int BODY_SNIPPET_MAX = 300;

    /** HTTP 状态码；网络层异常（连接失败/超时）时为 null。 */
    private final Integer httpStatus;

    /** 响应体原文（已截断），可能为 null。 */
    private final String responseBody;

    public GraphitiClientException(String message, Integer httpStatus, String responseBody, Throwable cause) {
        super(message, cause);
        this.httpStatus = httpStatus;
        this.responseBody = responseBody;
    }

    /** 构造一条标准化的调用失败异常。 */
    public static GraphitiClientException of(String operation,
                                             Integer httpStatus,
                                             String responseBody,
                                             Throwable cause) {
        String statusPart = httpStatus == null ? "网络异常" : ("HTTP " + httpStatus);
        String bodyPart = snippet(responseBody);
        String message = "Graphiti 调用失败 [" + operation + "] " + statusPart
                + (bodyPart.isEmpty() ? "" : " | " + bodyPart);
        return new GraphitiClientException(message, httpStatus, responseBody, cause);
    }

    public Integer httpStatus() {
        return httpStatus;
    }

    public String responseBody() {
        return responseBody;
    }

    /**
     * 是否是「限流 / 配额」类错误（429 或 403）。
     *
     * <p>★ 铁律 3：这两类必须优先识别，走最长退避（2min / 10min），
     * <b>绝不能</b>按普通 4xx 当毒消息丢弃 —— 否则高峰期会被成批清理。
     * 完整分类（含 body 关键词兜底）由 B1 的 {@code GraphitiErrorClassifier} 实现，
     * 这里先钉死「码」这一层。
     */
    public boolean isRateLimitedOrQuota() {
        return httpStatus != null && (httpStatus == 429 || httpStatus == 403);
    }

    private static String snippet(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        String trimmed = body.strip();
        return trimmed.length() <= BODY_SNIPPET_MAX
                ? trimmed
                : trimmed.substring(0, BODY_SNIPPET_MAX) + "...(截断)";
    }
}
