package com.yangmf.mini_nodepad;

import com.yangmf.mini_nodepad.client.GraphitiClientException;
import com.yangmf.mini_nodepad.client.GraphitiErrorClassifier;
import com.yangmf.mini_nodepad.client.GraphitiErrorClassifier.Kind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GraphitiErrorClassifier} 单测 —— <b>全链路最关键的分类器</b>。
 *
 * <p>为什么把它排在第一优先：这是唯一决定「消息会不会被永久丢掉」的判据。
 * 它一旦回归，症状不是报错，而是<b>用户的新偏好成批静默消失</b>
 * （尤其是 429 被误判成 TOXIC 时 —— 晚高峰的限流会被当成载荷错误直接进 DLQ）。
 *
 * <p>本类零依赖（不启 Spring），毫秒级，可进 CI。
 */
@DisplayName("L0-1 GraphitiErrorClassifier —— 失败分类（决定消息存亡）")
class GraphitiErrorClassifierTest {

    /** 构造一条带状态码的失败。 */
    private static GraphitiClientException err(Integer status, String body) {
        return GraphitiClientException.of("POST /messages", status, body, null);
    }

    private static GraphitiClientException err(Integer status) {
        return err(status, null);
    }

    // ==================== ① 显式限流（铁律 3 底线）====================

    @Nested
    @DisplayName("① 显式限流：429 / 403 必须走最长退避，绝不能判死")
    class ExplicitRateLimit {

        @Test
        @DisplayName("429 → RATE_LIMITED（铁律 3 底线：晚高峰限流不是毒消息）")
        void status429IsRateLimited() {
            assertEquals(Kind.RATE_LIMITED, GraphitiErrorClassifier.classify(err(429)));
        }

        @Test
        @DisplayName("403 → RATE_LIMITED（内网无鉴权层，403 只可能是上游配额，见附录 E.1）")
        void status403IsRateLimited() {
            assertEquals(Kind.RATE_LIMITED, GraphitiErrorClassifier.classify(err(403)));
        }

        @Test
        @DisplayName("429 即使 body 是空的也必须归限流（不能因线索缺失而倒向判死）")
        void status429WithBlankBodyIsStillRateLimited() {
            assertEquals(Kind.RATE_LIMITED, GraphitiErrorClassifier.classify(err(429, "   ")));
        }

        @Test
        @DisplayName("契约层 isRateLimitedOrQuota() 与分类器结论一致（两处不得分叉）")
        void contractHelperAgreesWithClassifier() {
            assertTrue(err(429).isRateLimitedOrQuota());
            assertTrue(err(403).isRateLimitedOrQuota());
            assertTrue(GraphitiErrorClassifier.isRateLimited(err(429)));
            assertTrue(GraphitiErrorClassifier.isRateLimited(err(403)));
        }
    }

    // ==================== ② body 关键词兜底「被包装过的限流」====================

    @Nested
    @DisplayName("② body 关键词兜底：4xx 里的限流语义必须被认出来")
    class WrappedRateLimit {

        @ParameterizedTest(name = "status={0} body={1} → RATE_LIMITED")
        @CsvSource(delimiter = '|', value = {
                "400 | rate limit exceeded",
                "404 | quota exceeded",
                "400 | THROTTLING",
                "400 | Too Many Requests",
                "400 | rate_limit",
                "400 | ratelimit",
                "422 | {\"detail\":\"upstream quota exhausted\"}",
                "409 | Rate Limit Exceeded: retry later",
        })
        @DisplayName("被包装成任意 4xx 的限流 → RATE_LIMITED（关键词是唯一线索）")
        void wrappedRateLimitKeywords(int status, String body) {
            assertEquals(Kind.RATE_LIMITED, GraphitiErrorClassifier.classify(err(status, body)),
                    () -> "限流关键词未被识别 → 该消息会被当毒消息永久丢弃 | status=" + status + " body=" + body);
        }

        @Test
        @DisplayName("关键词匹配不区分大小写（THROTTLING / Throttling / throttling 等价）")
        void keywordMatchIsCaseInsensitive() {
            for (String body : List.of("THROTTLING", "Throttling", "throttling", "tHrOtTlInG")) {
                assertEquals(Kind.RATE_LIMITED, GraphitiErrorClassifier.classify(err(400, body)),
                        () -> "大小写变体漏匹配: " + body);
            }
        }
    }

    // ==================== ③ 真毒消息 ====================

    @Nested
    @DisplayName("③ 真毒消息：4xx 且无任何限流线索 → TOXIC（重试无意义）")
    class Toxic {

        @ParameterizedTest(name = "status={0} → TOXIC")
        @ValueSource(ints = {400, 422, 401, 404, 405, 409, 410, 499})
        @DisplayName("常见 4xx 无关键词 → TOXIC")
        void plain4xxIsToxic(int status) {
            assertEquals(Kind.TOXIC, GraphitiErrorClassifier.classify(err(status, "字段校验失败：content 不是字符串")),
                    () -> "status=" + status + " 的载荷错误应判死，而不是无限重试");
        }

        @ParameterizedTest(name = "status={0} body 为空 → TOXIC（不得误入限流分支）")
        @NullSource
        @ValueSource(strings = {"", "   ", "\n\t "})
        @DisplayName("空体不得被当成限流（空白不是关键词）")
        void blankBodyOn4xxIsToxic(String body) {
            assertEquals(Kind.TOXIC, GraphitiErrorClassifier.classify(err(400, body)),
                    () -> "空白 body 被误判为限流 → 会把真毒消息重试 3 次后才隔离 | body=" + body);
        }
    }

    // ==================== ④ 可重试（5xx / 网络层）====================

    @Nested
    @DisplayName("④ 可重试：5xx 与网络层异常（含「DashScope 429 被包装成 500」）")
    class Retryable {

        @ParameterizedTest(name = "status={0} → RETRYABLE")
        @ValueSource(ints = {500, 501, 502, 503, 504, 599})
        @DisplayName("5xx 一律可重试（不需要关键词 —— 这条已兜住 DashScope 429→500 的包装）")
        void serverErrorsAreRetryable(int status) {
            assertEquals(Kind.RETRYABLE, GraphitiErrorClassifier.classify(err(status, "Internal Server Error")));
        }

        @Test
        @DisplayName("★ 5xx 即使 body 完全为空也必须可重试（不能因线索缺失倒向判死）")
        void serverErrorWithBlankBodyIsRetryable() {
            assertEquals(Kind.RETRYABLE, GraphitiErrorClassifier.classify(err(500)),
                    "5xx 判死 = 服务端短暂抖动造成永久数据丢失");
        }

        @Test
        @DisplayName("无状态码（连接拒绝 / 读超时）→ RETRYABLE")
        void networkFailureWithoutStatusIsRetryable() {
            assertEquals(Kind.RETRYABLE, GraphitiErrorClassifier.classify(err(null)));
        }

        @Test
        @DisplayName("★ null 异常 → RETRYABLE（保守：歧义一律倒向可重试）")
        void nullErrorIsRetryable() {
            assertEquals(Kind.RETRYABLE, GraphitiErrorClassifier.classify(null));
        }

        @ParameterizedTest(name = "status={0} → RETRYABLE（不属任何已知类别）")
        @ValueSource(ints = {301, 302, 304, 200, 204})
        @DisplayName("3xx / 2xx 等非预期码 → RETRYABLE（保守兜底）")
        void unexpectedStatusCodesAreRetryable(int status) {
            assertEquals(Kind.RETRYABLE, GraphitiErrorClassifier.classify(err(status)));
        }
    }

    // ==================== ⑤ 分类完备性与互斥性 ====================

    @Test
    @DisplayName("★ 分类结果完备，且三个谓词与 Kind 语义严格一致（含「限流也算可重试」这条刻意的包含关系）")
    void classificationIsExhaustiveAndPredicatesMatchKind() {
        // ⚠️ 注意：三个谓词【不是】互斥的 —— isRetryable 是「非毒消息」的超集，
        //    限流同时 isRateLimited 与 isRetryable（限流只是退避档位不同，不是不可重试）。
        //    这正是铁律 3 的落点，写成本测试可防止有人「顺手」把它改成互斥语义 ——
        //    那样限流会被排除出重试路径，等于把限流当毒消息丢弃。
        List<GraphitiClientException> samples = List.of(
                err(429), err(403),
                err(400, "rate limit exceeded"), err(400, "quota exceeded"),
                err(400, "字段校验失败"), err(422, "unprocessable"),
                err(500), err(503), err(null), err(302), err(200), err(499));

        for (GraphitiClientException e : samples) {
            Kind kind = GraphitiErrorClassifier.classify(e);
            assertNotNull(kind, () -> "classify 返回 null → 调用方分支会 NPE | " + describe(e));

            final Kind k = kind;
            final String detail = describe(e);

            assertEquals(kind == Kind.TOXIC, GraphitiErrorClassifier.isToxic(e),
                    () -> "isToxic 与 Kind 不一致 | " + detail + " kind=" + k);
            assertEquals(kind == Kind.RATE_LIMITED, GraphitiErrorClassifier.isRateLimited(e),
                    () -> "isRateLimited 与 Kind 不一致 | " + detail + " kind=" + k);
            assertEquals(kind != Kind.TOXIC, GraphitiErrorClassifier.isRetryable(e),
                    () -> "isRetryable 必须等价于「非毒消息」| " + detail + " kind=" + k);

            if (kind == Kind.TOXIC) {
                assertFalse(GraphitiErrorClassifier.isRetryable(e),
                        () -> "毒消息不得同时可重试（否则会无限重投到死）| " + detail);
            }
            if (kind == Kind.RATE_LIMITED) {
                assertTrue(GraphitiErrorClassifier.isRetryable(e),
                        () -> "★ 限流被排除出重试路径 = 把晚高峰的限流当载荷错误永久丢弃（铁律 3）| " + detail);
                assertFalse(GraphitiErrorClassifier.isToxic(e),
                        () -> "限流不得被判为毒消息 | " + detail);
            }
        }
    }

    @Test
    @DisplayName("isRetryable 含限流（限流只是档位不同，不是不可重试）")
    void rateLimitedIsAlsoRetryable() {
        assertTrue(GraphitiErrorClassifier.isRetryable(err(429)),
                "限流若被算作不可重试，等于把限流当毒消息 —— 铁律 3 的正是这一条");
        assertFalse(GraphitiErrorClassifier.isToxic(err(429)));
    }

    // ==================== ⑥ 退避档位 ====================

    @Nested
    @DisplayName("⑥ backoffLevel：限流强制跳过 30s 档")
    class Backoff {

        @ParameterizedTest(name = "attempt={0} kind={1} → 档位 {2}")
        @CsvSource({
                // ★ 限流跳过 30s 档（持续撞墙毫无帮助，反而把配额窗口填满）
                "0, RATE_LIMITED,  1",
                "1, RATE_LIMITED,  1",
                "2, RATE_LIMITED,  2",
                // 普通可重试按 attempt 递增
                "0, RETRYABLE,     0",
                "1, RETRYABLE,     1",
                "2, RETRYABLE,     2",
                // clamp 到 MAX_ATTEMPT-1
                "5, RETRYABLE,     2",
                "99, RATE_LIMITED, 2",
                // 负数防御
                "-1, RETRYABLE,    0",
                "-1, RATE_LIMITED, 1",
        })
        @DisplayName("档位计算（0=30s，1=2min，2=10min）")
        void backoffLevels(int attempt, Kind kind, int expected) {
            assertEquals(expected, GraphitiErrorClassifier.backoffLevel(attempt, kind));
        }

        @Test
        @DisplayName("★ 档位永不越界：任意 attempt × 任意 kind 的结果都在 [0, MAX_ATTEMPT-1]")
        void levelNeverOutOfRange() {
        for (int attempt = -5; attempt <= 10; attempt++) {
            for (Kind kind : Kind.values()) {
                int level = GraphitiErrorClassifier.backoffLevel(attempt, kind);
                // for 循环变量不是 effectively-final，必须复制成 final 才能进 lambda
                final int a = attempt;
                final Kind k = kind;
                assertTrue(level >= 0 && level < GraphitiErrorClassifier.MAX_ATTEMPT,
                        () -> "档位越界 → 会取到不存在的重试队列 | attempt=" + a + " kind=" + k
                                + " level=" + level);
            }
        }
        }

        @Test
        @DisplayName("isExhausted：恰好到 MAX_ATTEMPT 才耗尽")
        void exhaustionBoundary() {
            assertFalse(GraphitiErrorClassifier.isExhausted(0));
            assertFalse(GraphitiErrorClassifier.isExhausted(GraphitiErrorClassifier.MAX_ATTEMPT - 1));
            assertTrue(GraphitiErrorClassifier.isExhausted(GraphitiErrorClassifier.MAX_ATTEMPT),
                    "恰好耗尽时必须判死，否则会无限重试");
            assertTrue(GraphitiErrorClassifier.isExhausted(GraphitiErrorClassifier.MAX_ATTEMPT + 1));
        }
    }

    @Test
    @DisplayName("isSameKind 是值比较（避免调用方用字符串比较）")
    void isSameKindComparesValue() {
        assertTrue(GraphitiErrorClassifier.isSameKind(Kind.TOXIC, Kind.TOXIC));
        assertFalse(GraphitiErrorClassifier.isSameKind(Kind.TOXIC, Kind.RETRYABLE));
        assertTrue(GraphitiErrorClassifier.isSameKind(null, null));
        assertFalse(GraphitiErrorClassifier.isSameKind(Kind.TOXIC, null));
    }

    private static String describe(GraphitiClientException e) {
        return e == null ? "null" : "status=" + e.httpStatus() + " body=" + e.responseBody();
    }
}
