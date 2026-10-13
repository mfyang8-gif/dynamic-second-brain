package com.yangmf.mini_nodepad;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.yangmf.mini_nodepad.ai.component.PromptSanitizer;
import com.yangmf.mini_nodepad.client.GraphitiClientException;
import com.yangmf.mini_nodepad.client.GraphitiMemoryClient;
import com.yangmf.mini_nodepad.client.GroupIdResolver;
import com.yangmf.mini_nodepad.client.dto.EpisodeRef;
import com.yangmf.mini_nodepad.client.dto.GraphitiMessage;
import com.yangmf.mini_nodepad.client.dto.GraphitiRoleType;
import com.yangmf.mini_nodepad.client.dto.GraphitiWriteAck;
import com.yangmf.mini_nodepad.client.dto.RecalledFact;
import com.yangmf.mini_nodepad.client.dto.RecalledFactWithLifecycle;
import com.yangmf.mini_nodepad.client.impl.GraphitiMemoryClientImpl;
import com.yangmf.mini_nodepad.properties.GraphitiProperties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Graphiti 契约层测试（B0）—— <b>离线</b>跑，不需要真实 Graphiti 服务。
 *
 * <p>做法：用 JDK 自带的 {@code com.sun.net.httpserver.HttpServer} 在随机端口起一个 stub，
 * 把 {@link GraphitiMemoryClientImpl} 指过去，然后<b>逐条钉死</b>设计文档附录 B 里的字段级陷阱。
 *
 * <p>为什么值得写这么细：这些陷阱的共同特征是「<b>出错时悄无声息</b>」——
 * 漏一个字段是 422（还算好的）、传了 uuid 是「HTTP 202 但后台静默丢数据」、
 * {@code group_ids} 漏传是「跨租户检索且零报错」、时间格式不匹配是「解析异常」。
 * 它们全部无法靠联调时的「看起来正常」发现，只能靠断言锁住。
 */
class GraphitiContractTest {

    private static HttpServer server;
    private static int port;
    private static final ObjectMapper JSON = new ObjectMapper();

    /** stub 收到的请求体（每次请求一条，按到达顺序）。 */
    private static final List<String> REQUEST_BODIES = new CopyOnWriteArrayList<>();
    /** stub 收到的请求行（方法 + URI）。 */
    private static final List<String> REQUEST_LINES = new CopyOnWriteArrayList<>();
    /** stub 收到的 Content-Type（用于锁死「请求体是 JSON 而不是 YAML」）。 */
    private static final List<String> REQUEST_CONTENT_TYPES = new CopyOnWriteArrayList<>();

    /** 当前 stub 的响应策略，按请求路径返回。 */
    private static volatile Function<String, StubResponse> stubHandler =
            path -> new StubResponse(200, "{}");

    private record StubResponse(int status, String body) {
    }

    @BeforeAll
    static void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.createContext("/", exchange -> {
            byte[] raw = exchange.getRequestBody().readAllBytes();
            REQUEST_BODIES.add(new String(raw, StandardCharsets.UTF_8));
            REQUEST_LINES.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            REQUEST_CONTENT_TYPES.add(String.valueOf(exchange.getRequestHeaders().getFirst("Content-Type")));

            StubResponse response = stubHandler.apply(exchange.getRequestURI().getPath());
            byte[] out = response.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(response.status(), out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
        server.start();
    }

    @AfterAll
    static void stopStub() {
        if (server != null) {
            server.stop(0);
        }
    }

    @BeforeEach
    void resetStub() {
        REQUEST_BODIES.clear();
        REQUEST_LINES.clear();
        REQUEST_CONTENT_TYPES.clear();
        stubHandler = path -> new StubResponse(200, "{}");
    }

    private GraphitiMemoryClient newClient() {
        GraphitiProperties properties = new GraphitiProperties();
        properties.setBaseUrl("http://127.0.0.1:" + port);
        return new GraphitiMemoryClientImpl(properties, new GroupIdResolver(), new PromptSanitizer());
    }

    private static JsonNode lastRequestJson() throws Exception {
        String raw = REQUEST_BODIES.get(REQUEST_BODIES.size() - 1);
        try {
            return JSON.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException("stub 收到的请求体不是合法 JSON → [" + raw + "]", e);
        }
    }

    // ==================== 写入侧契约 ====================

    @Test
    @DisplayName("写入请求体：group_id + messages[] 包装，且四个字段陷阱全部满足")
    void writeRequestMatchesServerSchema() throws Exception {
        stubHandler = path -> new StubResponse(200, "{\"message\":\"ok\",\"success\":true}");

        OffsetDateTime ts = OffsetDateTime.parse("2026-10-13T01:02:03Z");
        GraphitiMessage message = GraphitiMessage.forChatMessage("101", "我偏好 Go 语言",
                GraphitiRoleType.USER, ts, "用户与助手的对话记录");

        GraphitiWriteAck ack = newClient().write(3L, message);
        assertTrue(ack.accepted(), "success=true 应被识别为已受理");

        JsonNode body = lastRequestJson();

        // 陷阱 1：messages 必须是数组（漏包一层 = 422）
        assertEquals("user_3", body.get("group_id").asText(), "group_id 必须由 GroupIdResolver 生成");
        assertTrue(body.get("messages").isArray(), "messages 必须是数组");
        assertEquals(1, body.get("messages").size(), "设计上 N=1 逐条投递（§3.9）");

        JsonNode item = body.get("messages").get(0);

        // 陷阱 31：uuid 必须为 null（非 null = update 已存在 episode = 静默丢数据）
        assertTrue(item.has("uuid"), "uuid 字段必须出现");
        assertTrue(item.get("uuid").isNull(), "uuid 必须为 null");

        // 陷阱 2：role 字段必须出现（服务端无 default，字段缺失即 422）
        assertTrue(item.has("role"), "role 字段必须出现（服务端无默认值，缺失即 422）");
        assertEquals("user", item.get("role").asText());

        // 陷阱 3：role_type 必须是小写字面量
        assertEquals("user", item.get("role_type").asText(), "role_type 必须是 user/assistant/system 小写");

        // 陷阱 33：name 承载确认锚点
        assertEquals("cm_101", item.get("name").asText(), "确认锚点必须是 cm_ + chatMessageId");

        // 陷阱 4：timestamp 必须显式传且带 offset
        assertTrue(item.has("timestamp"), "timestamp 必须出现在请求体里");
        OffsetDateTime parsed = OffsetDateTime.parse(item.get("timestamp").asText());
        assertNotNull(parsed.getOffset(), "时间戳必须携带 offset，否则服务端会按处理时刻处理");
        assertEquals(ts.toInstant(), parsed.toInstant());
    }

    @Test
    @DisplayName("时间戳以 ISO-8601 下发（不是 epoch 数字）")
    void timestampIsIsoNotEpoch() throws Exception {
        stubHandler = path -> new StubResponse(200, "{\"success\":true}");

        GraphitiMessage message = GraphitiMessage.forChatMessage("7", "内容",
                GraphitiRoleType.USER, OffsetDateTime.parse("2026-10-13T01:02:03Z"), null);
        newClient().write(1L, message);

        assertTrue(lastRequestJson().get("messages").get(0).get("timestamp").isTextual(),
                "时间戳必须是 ISO 字符串 —— 写成 epoch 数字服务端解析不了");
    }

    @Test
    @DisplayName("DTO 防御：uuid 非 null / timestamp 为 null / content 为空 一律构造期拒绝")
    void messageConstructorRejectsContractViolations() {
        OffsetDateTime ts = OffsetDateTime.parse("2026-10-13T01:02:03Z");

        IllegalArgumentException uuidError = assertThrows(IllegalArgumentException.class, () ->
                new GraphitiMessage("内容", "some-uuid", "cm_1", GraphitiRoleType.USER, "user", ts, null));
        assertTrue(uuidError.getMessage().contains("uuid"), "异常信息应点明 uuid 约束");

        assertThrows(IllegalArgumentException.class, () ->
                new GraphitiMessage("内容", null, "cm_1", GraphitiRoleType.USER, "user", null, null));

        assertThrows(IllegalArgumentException.class, () ->
                new GraphitiMessage("  ", null, "cm_1", GraphitiRoleType.USER, "user", ts, null));
    }

    @Test
    @DisplayName("role 传 null 时归一为 user（字段仍出现在 JSON 中）")
    void nullRoleFallsBackToDefault() throws Exception {
        stubHandler = path -> new StubResponse(200, "{\"success\":true}");

        GraphitiMessage message = new GraphitiMessage("内容", null, "cm_9",
                GraphitiRoleType.ASSISTANT, null, OffsetDateTime.parse("2026-10-13T01:02:03Z"), null);
        newClient().write(1L, message);

        JsonNode item = lastRequestJson().get("messages").get(0);
        assertTrue(item.has("role"), "role 字段必须出现");
        assertEquals("user", item.get("role").asText());
    }

    @Test
    @DisplayName("role_type 越界取值直接抛错（不静默降级）")
    void roleTypeRejectsUnknownValue() {
        assertThrows(IllegalArgumentException.class, () -> GraphitiRoleType.fromWire("robot"));
        assertEquals(GraphitiRoleType.SYSTEM, GraphitiRoleType.fromWire("SYSTEM"));
    }

    // ==================== 召回侧契约 ====================

    @Test
    @DisplayName("★ 宽拉取 → 严过滤 → 后截断（顺序颠倒会召回为空）")
    void recallFetchesWideThenFiltersThenTruncates() throws Exception {
        // 前 4 条匹配度最高但全部已作废，第 5 条才是当前有效的 —— 正是「用户改口」的真实形态
        stubHandler = path -> new StubResponse(200, """
                {"facts":[
                  {"uuid":"f1","fact":"已作废偏好1","created_at":"2026-10-12T09:00:00.000000+00:00","invalid_at":"2026-10-12T10:00:00.000000+00:00","episodes":[]},
                  {"uuid":"f2","fact":"已作废偏好2","created_at":"2026-10-12T09:01:00.000000+00:00","invalid_at":"2026-10-12T10:01:00.000000+00:00","episodes":[]},
                  {"uuid":"f3","fact":"已作废偏好3","created_at":"2026-10-12T09:02:00.000000+00:00","invalid_at":"2026-10-12T10:02:00.000000+00:00","episodes":[]},
                  {"uuid":"f4","fact":"已作废偏好4","created_at":"2026-10-12T09:03:00.000000+00:00","invalid_at":"2026-10-12T10:03:00.000000+00:00","episodes":[]},
                  {"uuid":"f5","fact":"当前有效偏好","created_at":"2026-10-13T01:00:00.000000+00:00","invalid_at":null,"episodes":[]}
                ]}
                """);

        List<RecalledFact> result = newClient().recallActive(3L, "我的偏好", 1);

        // 请求侧：max_facts 必须被放大为 limit × fetchFactor
        JsonNode request = lastRequestJson();
        assertEquals("user_3", request.get("group_ids").get(0).asText());
        assertEquals(4, request.get("max_facts").asInt(),
                "limit=1 且 fetchFactor=4 → 请求 4 条（补丁 2 的宽拉取）");

        // 返回侧：必须是第 5 条（若先截断后过滤，这里会是空列表）
        assertEquals(1, result.size(), "过滤后的有效事实必须被拉回来，不能因为 Top-N 截断而丢");
        assertEquals("f5", result.get(0).factId());
        assertEquals("当前有效偏好", result.get(0).statement());
    }

    @Test
    @DisplayName("召回丢弃已作废事实")
    void recallDropsInvalidatedFacts() throws Exception {
        stubHandler = path -> new StubResponse(200, """
                {"facts":[
                  {"uuid":"a","fact":"有效A","created_at":"2026-10-13T01:00:00.000000+00:00","invalid_at":null},
                  {"uuid":"b","fact":"作废B","created_at":"2026-10-12T01:00:00.000000+00:00","invalid_at":"2026-10-12T02:00:00.000000+00:00"},
                  {"uuid":"c","fact":"有效C","created_at":"2026-10-13T02:00:00.000000+00:00","invalid_at":null}
                ]}
                """);

        List<RecalledFact> result = newClient().recallActive(3L, "查询", 5);
        assertEquals(2, result.size());
        assertEquals(List.of("a", "c"), result.stream().map(RecalledFact::factId).toList());
    }

    @Test
    @DisplayName("只设 expired_at 的边会被 WARN 观测到，但不改判据（仍视为有效）")
    void lifecycleAnomalyIsObservableButNotFiltered() throws Exception {
        stubHandler = path -> new StubResponse(200, """
                {"facts":[
                  {"uuid":"x","fact":"只设了 expired 的边","created_at":"2026-10-13T01:00:00.000000+00:00",
                   "invalid_at":null,"expired_at":"2026-10-13T03:00:00.000000+00:00"}
                ]}
                """);

        List<RecalledFact> result = newClient().recallActive(3L, "查询", 5);
        assertEquals(1, result.size(), "作废判据只看 invalid_at（§2.6），expired_at 不参与");
    }

    @Test
    @DisplayName("召回结果必须过消毒器：超长事实被丢弃")
    void recallSanitizesFacts() throws Exception {
        String tooLong = "偏好".repeat(150); // 300 字 > PromptSanitizer.MAX_FACT_LENGTH(200)
        stubHandler = path -> new StubResponse(200, """
                {"facts":[
                  {"uuid":"long","fact":"%s","created_at":"2026-10-13T01:00:00.000000+00:00","invalid_at":null},
                  {"uuid":"ok","fact":"正常长度的偏好","created_at":"2026-10-13T01:00:00.000000+00:00","invalid_at":null}
                ]}
                """.formatted(tooLong));

        List<RecalledFact> result = newClient().recallActive(3L, "查询", 5);
        assertEquals(1, result.size(), "超长事实（投毒载荷形态）必须被丢弃");
        assertEquals("ok", result.get(0).factId());
    }

    @Test
    @DisplayName("旁路召回：不过滤作废，且带生命周期状态")
    void bypassRecallKeepsInvalidatedFacts() throws Exception {
        stubHandler = path -> new StubResponse(200, """
                {"facts":[
                  {"uuid":"old","fact":"旧偏好","valid_at":"2026-10-01T00:00:00Z","created_at":"2026-10-01T00:00:00.000000+00:00","invalid_at":"2026-10-10T00:00:00.000000+00:00"},
                  {"uuid":"new","fact":"新偏好","valid_at":"2026-10-10T00:00:00Z","created_at":"2026-10-10T00:00:00.000000+00:00","invalid_at":null}
                ]}
                """);

        List<RecalledFactWithLifecycle> result = newClient().recallIncludingInvalidated(3L, "偏好", 5);
        assertEquals(2, result.size(), "旁路必须保留已作废事实");
        assertEquals(RecalledFactWithLifecycle.Lifecycle.SUPERSEDED, result.get(0).status());
        assertEquals(RecalledFactWithLifecycle.Lifecycle.ACTIVE, result.get(1).status());
        assertNotNull(result.get(0).invalidAt());
    }

    @Test
    @DisplayName("user_id 非法时禁止生成 group_id（跨租户防线的第一道）")
    void groupIdResolverRejectsInvalidUserId() {
        GroupIdResolver resolver = new GroupIdResolver();
        assertEquals("user_3", resolver.resolve(3L));
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve(null));
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve(0L));
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve(-1L));
    }

    @Test
    @DisplayName("输入防御：limit / lastN / query / content 的非法与超限一律快速失败")
    void inputGuardsRejectInvalidArguments() {
        assertThrows(IllegalArgumentException.class, () -> newClient().recallActive(3L, "查询", 0));
        assertThrows(IllegalArgumentException.class, () -> newClient().recallActive(3L, "查询", -1));

        // /episodes 无分页无游标 → 窗口必须既有下限也有上限，否则窗口算错即「拉全表」
        assertThrows(IllegalArgumentException.class, () -> newClient().listEpisodes(3L, 0));
        assertThrows(IllegalArgumentException.class, () -> newClient().listEpisodes(3L, 10_000));

        // 检索语句不是载荷通道
        assertThrows(IllegalArgumentException.class,
                () -> newClient().recallActive(3L, "问".repeat(2001), 5));

        // 单条消息体积上限（防上游把整篇文档当对话投递）
        assertThrows(IllegalArgumentException.class, () -> GraphitiMessage.forChatMessage(
                "1", "字".repeat(8001), GraphitiRoleType.USER, OffsetDateTime.now(), null));
    }

    // ==================== 时间格式与错误分支 ====================

    @Test
    @DisplayName("两种时间格式都能解析：/search 的 +00:00 微秒 与 /episodes 的 Z")
    void bothTimeFormatsParse() throws Exception {
        stubHandler = path -> new StubResponse(200, """
                {"facts":[
                  {"uuid":"t1","fact":"带 offset 的事实",
                   "created_at":"2026-10-12T09:51:56.345970+00:00","invalid_at":null,
                   "valid_at":"2026-10-12T09:51:56.345970+00:00"}
                ]}
                """);
        List<RecalledFact> facts = newClient().recallActive(3L, "查询", 5);
        assertEquals(1, facts.size());
        assertNotNull(facts.get(0).effectiveAt(), "带 offset 的微秒格式必须能落到 OffsetDateTime");

        stubHandler = path -> new StubResponse(200, """
                [{"uuid":"e1","name":"cm_55","source_description":"对话记录",
                  "created_at":"2026-10-12T09:51:56.345970Z","valid_at":"2026-10-12T09:51:56.345970Z",
                  "group_id":"user_3","content":"原文","entity_edges":[]}]
                """);
        List<EpisodeRef> episodes = newClient().listEpisodes(3L, 20);
        assertEquals(1, episodes.size());
        assertEquals("cm_55", episodes.get(0).name(), "/episodes 的 name 是确认锚点");
        assertNotNull(episodes.get(0).createdAt());

        // last_n 必须真的下发给服务端
        assertTrue(REQUEST_LINES.get(REQUEST_LINES.size() - 1).contains("last_n=20"),
                "/episodes 的 last_n 是必填参数");
    }

    @Test
    @DisplayName("非 2xx 抛 GraphitiClientException 并保留状态码；429/403 必须可识别")
    void httpErrorsBecomeClientException() {
        stubHandler = path -> new StubResponse(429, "{\"detail\":\"rate limited\"}");
        GraphitiClientException limited = assertThrows(GraphitiClientException.class,
                () -> newClient().recallActive(3L, "查询", 5));
        assertEquals(429, limited.httpStatus());
        assertTrue(limited.isRateLimitedOrQuota(), "429 必须能被识别，绝不能当普通 4xx 丢进 DLQ");

        stubHandler = path -> new StubResponse(422, "{\"detail\":\"validation error\"}");
        GraphitiClientException invalid = assertThrows(GraphitiClientException.class,
                () -> newClient().write(3L, GraphitiMessage.forChatMessage("1", "内容",
                        GraphitiRoleType.USER, OffsetDateTime.now(), null)));
        assertEquals(422, invalid.httpStatus());
        assertFalse(invalid.isRateLimitedOrQuota(), "422 不该走长退避");

        // 422 的响应体必须被带出（B1 的异常分类器需要 body 关键词兜底）
        assertNotNull(invalid.responseBody());
    }

    @Test
    @DisplayName("写入响应缺 success 字段时视为未受理")
    void writeWithoutSuccessFieldIsNotAccepted() {
        stubHandler = path -> new StubResponse(200, "{\"message\":\"queued\"}");
        GraphitiWriteAck ack = newClient().write(3L, GraphitiMessage.forChatMessage("1", "内容",
                GraphitiRoleType.USER, OffsetDateTime.now(), null));
        assertFalse(ack.accepted(), "缺少 success 不能当成功");
    }

    @Test
    @DisplayName("健康探测失败不抛异常（健康检查自身不该成为故障源）")
    void healthcheckNeverThrows() {
        stubHandler = path -> new StubResponse(500, "boom");
        String result = newClient().healthcheck();
        assertNotNull(result);
        assertTrue(result.startsWith("HTTP 500") || result.startsWith("DOWN:"), "应返回可读状态：" + result);
    }

    @Test
    @DisplayName("deleteEdge 走 DELETE /entity-edge/{uuid}")
    void deleteEdgeUsesDeleteVerb() {
        stubHandler = path -> new StubResponse(200, "{}");
        newClient().deleteEdge("edge-uuid-1");
        assertTrue(REQUEST_LINES.get(0).startsWith("DELETE /entity-edge/edge-uuid-1"),
                "实际请求行：" + REQUEST_LINES.get(0));
        assertThrows(IllegalArgumentException.class, () -> newClient().deleteEdge("  "));
    }

    @Test
    @DisplayName("无消费方仍可安全构建：配置默认值自洽")
    void propertiesDefaultsAreSane() {
        GraphitiProperties properties = new GraphitiProperties();
        assertFalse(properties.isEnabled(), "B0 阶段总开关必须默认关闭");
        assertFalse(properties.getWrite().isEnabled());
        assertFalse(properties.getRecall().isEnabled());
        assertEquals(4, properties.effectiveFetchFactor());
        assertEquals(2000, properties.getTimeout().getRecallReadMs());
        assertTrue(properties.getTimeout().getWriteReadMs() > properties.getTimeout().getRecallReadMs(),
                "写超时必须长于读超时（§2.5）");

        // 配置写错（0 / 负数）时不能变成「请求 0 条」这种静默故障
        properties.getRecall().setFetchFactor(0);
        assertEquals(1, properties.effectiveFetchFactor());
        properties.getRecall().setFetchFactor(-5);
        assertEquals(1, properties.effectiveFetchFactor());
    }

    @Test
    @DisplayName("search 请求必须携带非空 group_ids（跨租户检索的唯一防线）")
    void searchCarriesGroupIds() throws Exception {
        stubHandler = path -> new StubResponse(200, "{\"facts\":[]}");
        newClient().recallActive(7L, "查询", 3);

        JsonNode request = lastRequestJson();
        assertTrue(request.has("group_ids"), "字段名必须是复数 group_ids（写成单数会被静默丢弃）");
        assertEquals(1, request.get("group_ids").size());
        assertEquals("user_7", request.get("group_ids").get(0).asText());
        assertNull(request.get("group_id"), "绝不能同时下发单数字段");
    }

    @Test
    @DisplayName("★ 写入请求体必须是 JSON，不能被 YamlHttpMessageConverter 抢走（真 bug 回归守卫）")
    void writeBodyIsJsonNotYaml() throws Exception {
        stubHandler = path -> new StubResponse(200, "{\"success\":true,\"message\":\"queued\"}");
        newClient().write(3L, GraphitiMessage.forChatMessage("1", "内容",
                GraphitiRoleType.USER, OffsetDateTime.now(), null));

        String raw = REQUEST_BODIES.get(REQUEST_BODIES.size() - 1);

        // ① 首行不能是 YAML 文档标记：RestClient 的默认转换器列表里有 YamlHttpMessageConverter
        //    （snakeyaml 在 classpath 上即注册），其 supports() 对任意类型都返回 true；
        //    若把自建 Jackson 转换器 append 到末尾，未显式指定 Content-Type 时会被它抢走，
        //    请求体变成 YAML 文档（首行 "---"）→ 服务端 pydantic 直接 422。
        assertFalse(raw.stripLeading().startsWith("---"),
                "请求体被序列化成了 YAML 文档，服务端会 422 → [" + raw + "]");

        // ② 必须能当 JSON 解析（lastRequestJson 解析失败会抛 IllegalStateException）
        JsonNode body = lastRequestJson();
        assertTrue(body.has("group_id") && body.has("messages"),
                "JSON 结构不完整（group_id / messages 缺失）→ [" + raw + "]");

        // ③ 下发的 Content-Type 必须是 JSON —— 这是「转换器顺序正确」最直接的证据
        String contentType = REQUEST_CONTENT_TYPES.get(REQUEST_CONTENT_TYPES.size() - 1);
        assertTrue(contentType.contains("json"),
                "Content-Type 非 JSON，说明写入权被别的转换器抢走 → " + contentType);
    }

    @Test
    @DisplayName("★ 确认锚点：cm_ + chatMessageId 与其逆运算严格互逆（主键是 VARCHAR 不是数值）")
    void confirmAnchorRoundTrips() {
        // chat_message.id 实测为 VARCHAR（IdType.ASSIGN_UUID）→ 契约层必须用 String
        GraphitiMessage message = GraphitiMessage.forChatMessage("a1b2c3", "内容",
                GraphitiRoleType.USER, OffsetDateTime.now(), null);
        assertEquals("cm_a1b2c3", message.name(), "锚点格式是 B1 对账唯一的匹配依据");
        assertEquals("a1b2c3", GraphitiMessage.chatMessageIdOf("cm_a1b2c3"));

        // UUID 主键本身就带短横线，必须原样还原（不能做任何「清理」）
        assertEquals("ab-cd-ef", GraphitiMessage.chatMessageIdOf("cm_ab-cd-ef"));

        // ★ 前缀不匹配时必须返回 null，不能「静默 strip 掉前缀」：
        //   否则对账会把 Graphiti 里别人的 name 当成 chat_message.id 去 UPDATE，
        //   影响 0 行 → 该消息永远不会被标记 synced_at → 每轮都被判「投递失败」反复重投。
        assertNull(GraphitiMessage.chatMessageIdOf("other_a1b2c3"));
        assertNull(GraphitiMessage.chatMessageIdOf("cm_"));
        assertNull(GraphitiMessage.chatMessageIdOf(null));

        assertThrows(IllegalArgumentException.class, () -> GraphitiMessage.forChatMessage("  ", "内容",
                GraphitiRoleType.USER, OffsetDateTime.now(), null));
    }
}
