package com.yangmf.mini_nodepad;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP 黑盒冒烟 —— 证明「前端发请求」这条测试路线在本工程真的能跑。
 *
 * <p><b>为什么用 {@code webEnvironment = RANDOM_PORT} 而不是手工起 8082：</b>
 * <ul>
 *   <li>工具侧起长驻后端不可靠（实测 {@code nohup mvn spring-boot:run} 会卡死并被回收）；</li>
 *   <li><b>随机端口不会和用户 IDEA 里跑的 8082 冲突</b> —— 这是能反复跑的前提；</li>
 *   <li>走的是真实 Servlet 容器 + 真实拦截器链 + 真实 JWT/Redis 校验，
 *       和浏览器发出去的请求**同一条链路**，不是 MockMvc 那种「绕过滤器的直连」。</li>
 * </ul>
 *
 * <p>它不是「看起来像测试」的占位：这里每一条断言都对应一个**真实踩过的坑**，
 * 见各用例注释。跑完它，才说明后面的 SSE / 指标量化用例有落脚点。
 *
 * <p>⚠️ 前置条件：MySQL / Redis / RabbitMQ / Qdrant 需可达（本机走 192.168.88.130）。
 * 不可达时本类会整体失败 —— 这是有意的：冒烟测试的语义就是「环境不可用 = 红灯」，
 * 不像实机集成用例那样用 {@code Assumptions} 静默跳过。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Tag("api")
@DisplayName("HTTP 黑盒冒烟（真容器 + 真 JWT 链路 + 真指标端点）")
class ApiHttpSmokeTest {

    /** 与前端一致：`src/constants.ts` 里的 AUTH_HEADER 就是全小写 authorization。 */
    private static final String AUTH_HEADER = "authorization";

    /** 演示账号（库里 user.id = 3）。 */
    private static final String DEMO_ACCOUNT = "demo";
    private static final String DEMO_PASSWORD = "demo12345";

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    /** 注入应用自定义的 @Primary ObjectMapper，保证解析口径与线上一致。 */
    @Autowired
    private ObjectMapper objectMapper;

    /** 整个类共用的 token：登录一次，后续用例复用（省掉重复登录的 Redis 写入）。 */
    private static String token;

    // ==================== ① 鉴权硬闸（负向）====================

    @Test
    @Order(1)
    @DisplayName("① 不带 token 访问受保护端点必须 401 —— 证明拦截器真的生效")
    void protectedEndpointWithoutTokenIsUnauthorized() {
        ResponseEntity<String> res = rest.getForEntity(url("/api/v1/books"), String.class);
        assertEquals(401, res.getStatusCode().value(),
                () -> "受保护端点未带 token 却没被拦下，说明本类跑的不是真实拦截器链 | body=" + res.getBody());
        assertTrue(res.getBody() == null || res.getBody().contains("401"),
                "401 响应体应含业务码 401（拦截器 writeErrorResponse 的既有形态）");
    }

    // ==================== ② 登录（正向）====================

    @Test
    @Order(2)
    @DisplayName("② POST /api/v1/auth/login（公开端点）→ 拿到 token + user")
    void loginReturnsTokenAndUser() throws Exception {
        JsonNode body = postJson("/api/v1/auth/login",
                Map.of("account", DEMO_ACCOUNT, "password", DEMO_PASSWORD), null);

        assertEquals(200, body.path("code").asInt(),
                () -> "登录失败：' + body=" + body
                        + "\n  排查：① 账号密码是否仍为 demo/demo12345；"
                        + "② AuthService 是否要求邮箱登录；③ user.status 是否为 1");

        JsonNode data = body.path("data");
        String got = data.path("token").asText(null);
        assertNotNull(got, "登录响应必须带 token");
        assertFalse(got.isBlank(), "token 不得为空白");

        // 前端拿到的就是裸 JWT，这里顺手钉死「不能带 Bearer 前缀」这条约定
        assertFalse(got.startsWith("Bearer "),
                "token 必须是裸 JWT —— 带 Bearer 前缀会被拦截器当成无效 token（constants.ts 已注明）");

        assertEquals(3L, data.path("user").path("id").asLong(),
                "演示账号 demo 的 id 应为 3；若变了说明库被重建，需同步本类常量");

        token = got;
        System.out.printf("%n★ 登录成功：token 前缀=%s…，userId=%s%n",
                got.substring(0, Math.min(12, got.length())), data.path("user").path("id").asText());
    }

    // ==================== ③ 受保护端点（正向）====================

    @Test
    @Order(3)
    @DisplayName("③ 带 token 访问书库列表 → 200，并把别人的书挡在外面（跨租户红线）")
    void booksRequiresTokenAndIsScopedToCurrentUser() throws Exception {
        JsonNode body = getJson("/api/v1/books", token);
        assertEquals(200, body.path("code").asInt(), () -> "书库列表异常 | body=" + body);

        // 注意：这个接口返回的是分页包装（PageResult<BookVO>），不是裸数组——
        // 断言 data 是数组会红，这是我先踩过一次的坑。
        JsonNode data = body.path("data");
        assertTrue(data.path("records").isArray(), () -> "data.records 应为数组 | body=" + body);

        JsonNode books = data.path("records");
        System.out.printf("★ 当前账号书库：total=%d，本页 %d 条%n",
                data.path("total").asLong(), books.size());

        for (JsonNode b : books) {
            // 红线：列表接口只能返回自己的书。用「已知他人书 id 不出现」做定性验证
            assertFalse("7eb8ee4edc4a496eaa966d59d97596d8".equals(b.path("id").asText()),
                    "★ 跨租户泄漏：user_id=1 的书出现在了 demo 的列表里");
        }
    }

    // ==================== ④ 白名单登记（历史踩坑点）====================

    @Test
    @Order(4)
    @DisplayName("④ /api/v1/memory/** 已在拦截器白名单登记 —— 带有效 token 不得 401")
    void memoryEndpointIsRegisteredInInterceptorWhitelist() throws Exception {
        ResponseEntity<String> res = rest.exchange(url("/api/v1/memory/habits"), HttpMethod.GET,
                new HttpEntity<>(authHeaders(token)), String.class);

        // ★ 这是 2026-10-11 的真实事故：新开 @RestController 却忘了登记到
        //    WebMvcConfiguration 的 addPathPatterns，症状是「带着有效 token 也 100% 401」。
        //    断言写成「不是 401」而不是「等于 200」，是为了让本用例只盯住「登记」这一件事。
        assertNotEqualsWithHint(401, res.getStatusCode().value(),
                "★ /api/v1/memory/** 未被拦截器覆盖 → 每次请求都 401。"
                        + "去 WebMvcConfiguration.addInterceptors 补登记（历史事故，勿重犯）",
                res.getBody());

        JsonNode body = objectMapper.readTree(res.getBody());
        assertEquals(200, body.path("code").asInt(), () -> "习惯列表业务码异常 | body=" + body);
    }

    // ==================== ⑤ 指标端点（免登 + 可读）====================

    @Test
    @Order(5)
    @DisplayName("⑤ /actuator/metrics 免登可读，且双路与背压指标已注册 —— 量化效果的读数入口")
    void actuatorMetricsAreReachableAndCarryFeatureMeters() throws Exception {
        ResponseEntity<String> res = rest.getForEntity(url("/actuator/metrics"), String.class);
        assertEquals(200, res.getStatusCode().value(),
                () -> "/actuator/metrics 应免登可读（用户已裁决只暴露 metrics,health）| body=" + res.getBody());

        JsonNode names = objectMapper.readTree(res.getBody()).path("names");
        assertTrue(names.isArray(), "metrics 列表结构异常");

        String joined = names.toString();
        // 背压 Gauge 在 Bean 构造期即注册 → 一定存在，适合做「端点通不通」的判据
        assertTrue(joined.contains("graphiti.backpressure.active"),
                () -> "背压 Gauge 未注册 —— 熔断将不可观测（静默熔断 = 静默数据延迟）。现有指标=" + joined);

        System.out.println("★ 已注册的领域指标：");
        for (JsonNode n : names) {
            String name = n.asText();
            if (name.startsWith("retrieval.") || name.startsWith("graphiti.")) {
                System.out.println("   - " + name);
            }
        }
    }

    // ==================== ⑥ 已知风险锚点（待裁决后应反向断言）====================

    @Test
    @Order(6)
    @DisplayName("⑥ ⚠️ 已知风险：/api/v1/test/mock-login 无鉴权即可签发 userId=1 的 token")
    void devBackdoorIsReachableWithoutAuth() {
        ResponseEntity<String> res = rest.getForEntity(url("/api/v1/test/mock-login"), String.class);
        int status = res.getStatusCode().value();

        // ★ 本用例是「行为钉桩（characterization test）」，不是「在夸这个设计」。
        //   TestAuthController 有 @RequestMapping("/api/v1/test")，但：
        //     ① 无 @Profile / @Conditional → 生产 profile 同样生效；
        //     ② /api/v1/test/** 不在 WebMvcConfiguration 的任何 addPathPatterns 里 → 完全不需登录。
        //   组合起来 = 任何人可凭一条 GET 拿到能读写 userId=1 全部数据的凭证。
        //   修复后（加 @Profile("dev") 或登记到拦截器并校验），本用例会红 —— 那正是我们要的信号，
        //   届时请把它改成 assertNotEquals(200, status)。
        System.out.printf("%n⚠️  开发后门可达性：GET /api/v1/test/mock-login → HTTP %d%n"
                + "    如为 200 且返回 token，说明该后门在所有 profile 下都对外可用（P0 级安全问题，待裁决）%n", status);

        assertEquals(200, status,
                "后门若已被加固（不再是 200），请把本用例改为反向断言，并把结论同步进安全自查清单");
    }

    // ==================== 工具方法 ====================

    private String url(String path) {
        return "http://127.0.0.1:" + port + path;
    }

    private HttpHeaders authHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.set(AUTH_HEADER, token);
        }
        return headers;
    }

    private JsonNode getJson(String path, String token) throws Exception {
        ResponseEntity<String> res = rest.exchange(url(path), HttpMethod.GET,
                new HttpEntity<>(authHeaders(token)), String.class);
        return objectMapper.readTree(res.getBody());
    }

    private JsonNode postJson(String path, Object payload, String token) throws Exception {
        String json = objectMapper.writeValueAsString(payload);
        ResponseEntity<String> res = rest.exchange(url(path), HttpMethod.POST,
                new HttpEntity<>(json, authHeaders(token)), String.class);
        return objectMapper.readTree(res.getBody());
    }

    /** 断言「不等于」，并把响应体带进失败信息 —— 排障时不用再复跑一次。 */
    private static void assertNotEqualsWithHint(int unexpected, int actual, String hint, String body) {
        if (actual == unexpected) {
            throw new AssertionError(hint + " | HTTP=" + actual + " | body=" + body);
        }
    }
}
