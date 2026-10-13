package com.yangmf.mini_nodepad.client.impl;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.yangmf.mini_nodepad.ai.component.PromptSanitizer;
import com.yangmf.mini_nodepad.client.GraphitiClientException;
import com.yangmf.mini_nodepad.client.GraphitiMemoryClient;
import com.yangmf.mini_nodepad.client.GroupIdResolver;
import com.yangmf.mini_nodepad.client.dto.EpisodeRef;
import com.yangmf.mini_nodepad.client.dto.GraphitiFact;
import com.yangmf.mini_nodepad.client.dto.GraphitiMessage;
import com.yangmf.mini_nodepad.client.dto.GraphitiSearchRequest;
import com.yangmf.mini_nodepad.client.dto.GraphitiSearchResponse;
import com.yangmf.mini_nodepad.client.dto.GraphitiWriteAck;
import com.yangmf.mini_nodepad.client.dto.GraphitiWriteRequest;
import com.yangmf.mini_nodepad.client.dto.RecalledFact;
import com.yangmf.mini_nodepad.client.dto.RecalledFactWithLifecycle;
import com.yangmf.mini_nodepad.properties.GraphitiProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Graphiti 客户端实现（B0 契约层）。
 *
 * <p>本类的全部行为都在「不让某条已知契约陷阱变成线上事故」。两条最容易被顺手做错的硬约束，
 * 在构造期就与工程现有的公共设施<b>物理隔离</b>：
 *
 * <table border="1">
 *   <caption>两条硬约束</caption>
 *   <tr><th>约束</th><th>为什么</th></tr>
 *   <tr>
 *     <td>自建 {@code RestClient}，<b>绝不复用</b> {@code restTemplate} Bean</td>
 *     <td>{@code RestTemplateConfig} 的 read timeout 是 <b>60s</b>，写调用又跑在 MQ 消费线程上 ——
 *         一次超时就把消费线程钉死 1 分钟，直接拖垮整个写入链（陷阱 7）</td>
 *   </tr>
 *   <tr>
 *     <td>自建 {@code ObjectMapper}，<b>绝不共用</b>全局 {@code JacksonObjectMapper}</td>
 *     <td>全局 mapper 被注册为 {@code @Primary} 并把 {@code LocalDateTime} 钉成
 *         {@code yyyy-MM-dd'T'HH:mm:ss}（无 offset）；而 Graphiti 返回
 *         {@code 2026-10-12T09:51:56.345970+00:00}（微秒 + UTC offset）→ <b>解析必然抛异常</b>（陷阱 6）</td>
 *   </tr>
 * </table>
 *
 * <p>第三个「隔离」是超时：读操作必须远短于写操作（§2.5）。召回阻塞 SSE 首字，
 * 所以只给 2s（超时即降级为「无记忆」，用户无感知）；写入只要拿到 202，给 5s 足矣。
 */
@Slf4j
@Component
public class GraphitiMemoryClientImpl implements GraphitiMemoryClient {

    private static final String PATH_MESSAGES = "/messages";
    private static final String PATH_SEARCH = "/search";
    private static final String PATH_EPISODES = "/episodes/{groupId}";
    private static final String PATH_ENTITY_EDGE = "/entity-edge/{uuid}";
    private static final String PATH_HEALTHCHECK = "/healthcheck";

    private final GraphitiProperties properties;
    private final GroupIdResolver groupIdResolver;
    private final PromptSanitizer promptSanitizer;

    /** 召回（在线路径）：短超时，超时即降级。 */
    private final RestClient recallClient;
    /** 写入（MQ 路径）：只需 202。 */
    private final RestClient writeClient;
    /** 对账 / 健康探测（定时任务）：不阻塞任何用户路径。 */
    private final RestClient confirmClient;

    public GraphitiMemoryClientImpl(GraphitiProperties properties,
                                    GroupIdResolver groupIdResolver,
                                    PromptSanitizer promptSanitizer) {
        String baseUrl = properties.getBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("graphiti.base-url 未配置，Graphiti 客户端无法构建");
        }
        this.properties = properties;
        this.groupIdResolver = groupIdResolver;
        this.promptSanitizer = promptSanitizer;

        ObjectMapper graphitiMapper = buildGraphitiObjectMapper();
        GraphitiProperties.Timeout timeout = properties.getTimeout();
        this.recallClient = buildRestClient(baseUrl, timeout.getRecallConnectMs(), timeout.getRecallReadMs(), graphitiMapper);
        this.writeClient = buildRestClient(baseUrl, timeout.getWriteConnectMs(), timeout.getWriteReadMs(), graphitiMapper);
        this.confirmClient = buildRestClient(baseUrl, timeout.getConfirmConnectMs(), timeout.getConfirmReadMs(), graphitiMapper);

        log.info("Graphiti 契约层就绪 | baseUrl={} | 超时(ms) 召回 {}/{}、写入 {}/{}、对账 {}/{} | fetchFactor={}",
                baseUrl,
                timeout.getRecallConnectMs(), timeout.getRecallReadMs(),
                timeout.getWriteConnectMs(), timeout.getWriteReadMs(),
                timeout.getConfirmConnectMs(), timeout.getConfirmReadMs(),
                properties.effectiveFetchFactor());
    }

    // ==================== 召回 ====================

    @Override
    public List<RecalledFact> recallActive(Long userId, String query, int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit 必须为正数（当前 " + limit + "）：静默返回空会掩盖调用方 bug");
        }

        // ★ 铁律 5：宽拉取（limit × fetchFactor）→ 严过滤（invalidAt）→ 后截断，顺序不可颠倒。
        // 若截断先发生：用户改口频繁时「匹配度最高的 N 条恰好全部作废」会让有效事实（第 N+1 条）
        // 根本没进候选集 —— 结果是召回为空（功能故障），而不是精度下降（补丁 2 / 陷阱 20）。
        int fetchSize = limit * properties.effectiveFetchFactor();
        List<GraphitiFact> candidates = doSearch(userId, query, fetchSize);

        List<RecalledFact> result = new ArrayList<>(limit);
        for (GraphitiFact fact : candidates) {
            // ③ 过滤：作废判据只看 invalidAt（§2.6）
            if (!fact.isValid()) {
                continue;
            }
            // 观测点：只设 expired_at 的作废路径上游尚不存在，一旦出现先暴露事实再决定是否改判据
            if (fact.hasLifecycleAnomaly()) {
                log.warn("Graphiti 返回 invalid_at 为空但 expired_at 非空的边，可能上游新增了只设 expired_at 的作废路径 | factId={}",
                        fact.uuid());
            }
            // ④ 消毒：超长 / 空 / 含系统保留标签的一律丢弃（记忆会永久注入 System Prompt，危害高于单次输入）
            String statement = promptSanitizer.sanitizeFact(fact.fact());
            if (statement == null) {
                log.debug("召回事实被消毒器丢弃（空白 / 超长 / 含保留标签）| factId={}", fact.uuid());
                continue;
            }
            result.add(new RecalledFact(fact.uuid(), statement, effectiveAtOf(fact)));
            // ⑤ 截断：发生在过滤与消毒之后
            if (result.size() >= limit) {
                break;
            }
        }

        log.debug("Graphiti 召回 | userId={} | 请求 {} 条 → 命中 {} 条 → 有效 {} 条",
                userId, fetchSize, candidates.size(), result.size());
        return result;
    }

    @Override
    public List<RecalledFactWithLifecycle> recallIncludingInvalidated(Long userId, String query, int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit 必须为正数（当前 " + limit + "）");
        }
        // 旁路：不过滤 invalidAt，因此也无需宽拉取（放大只为补偿过滤造成的损耗）
        List<GraphitiFact> facts = doSearch(userId, query, limit);

        List<RecalledFactWithLifecycle> result = new ArrayList<>(facts.size());
        for (GraphitiFact fact : facts) {
            String statement = promptSanitizer.sanitizeFact(fact.fact());
            if (statement == null) {
                continue;
            }
            result.add(RecalledFactWithLifecycle.of(fact.uuid(), statement,
                    fact.validAt(), fact.invalidAt(), fact.expiredAt()));
        }
        return result;
    }

    // ==================== 写入 ====================

    @Override
    public GraphitiWriteAck write(Long userId, GraphitiMessage message) {
        if (message == null) {
            throw new IllegalArgumentException("message 不能为空");
        }
        // ★ 陷阱 1 的唯一防线：包装动作只发生在这里，调用方没有绕过它的余地
        GraphitiWriteRequest request = GraphitiWriteRequest.of(groupIdResolver.resolve(userId), message);

        GraphitiWriteAck ack = execute("write", () -> writeClient.post()
                .uri(PATH_MESSAGES)
                .body(request)
                .retrieve()
                .body(GraphitiWriteAck.class));

        if (ack == null) {
            // 契约要求返回 {message, success}；空响应体意味着无法判断是否受理，不能当成功吞掉
            throw GraphitiClientException.of("write", null, "响应体为空，无法确认受理状态", null);
        }
        log.debug("Graphiti 投递受理 | groupId={} | anchor={} | accepted={}",
                request.groupId(), message.name(), ack.accepted());
        return ack;
    }

    // ==================== 对账 / 运维 ====================

    @Override
    public List<EpisodeRef> listEpisodes(Long userId, int lastN) {
        if (lastN <= 0) {
            throw new IllegalArgumentException("lastN 必须为正数：该接口 last_n 必填且无游标（§3.6.1）");
        }
        if (lastN > MAX_EPISODES_WINDOW) {
            throw new IllegalArgumentException(
                    "lastN 超上限（" + lastN + " > " + MAX_EPISODES_WINDOW
                            + "）：/episodes 无分页，超大窗口会把整个分区一次性拉进内存");
        }
        String groupId = groupIdResolver.resolve(userId);

        EpisodeRef[] episodes = execute("listEpisodes", () -> confirmClient.get()
                .uri(uriBuilder -> uriBuilder.path("/episodes/{groupId}")
                        .queryParam("last_n", lastN)
                        .build(groupId))
                .retrieve()
                .body(EpisodeRef[].class));

        return episodes == null ? List.of() : List.of(episodes);
    }

    /**
     * {@code /episodes} 单次窗口上限（输入防御）。
     *
     * <p>该接口<b>没有分页也没有游标</b>，调用方给多大就返回多大。
     * 对账任务按「最老未确认目标的投递窗口」动态算 {@code last_n}（§3.6.1），
     * 一旦窗口计算写错（例如把时间戳当成条数）就会退化成「拉全表」——
     * 这里兜一道上限，让错误快速暴露而不是把内存吃光。
     */
    private static final int MAX_EPISODES_WINDOW = 500;

    @Override
    public void deleteEdge(String edgeUuid) {
        if (edgeUuid == null || edgeUuid.isBlank()) {
            throw new IllegalArgumentException("edgeUuid 不能为空");
        }
        execute("deleteEdge", () -> confirmClient.delete()
                .uri(PATH_ENTITY_EDGE, edgeUuid)
                .retrieve()
                .toBodilessEntity());
    }

    @Override
    public String healthcheck() {
        // 健康检查自身不该成为故障源：把异常收敛成可读文本，绝不外抛
        try {
            ResponseEntity<String> response = confirmClient.get()
                    .uri(PATH_HEALTHCHECK)
                    .retrieve()
                    .toEntity(String.class);
            return "HTTP " + response.getStatusCode().value();
        } catch (Exception e) {
            return "DOWN: " + e.getClass().getSimpleName() + " - " + e.getMessage();
        }
    }

    // ==================== 内部实现 ====================

    /**
     * 发起检索。{@code groupIds} 的非空断言收敛在 {@link GraphitiSearchRequest} 的构造期 ——
     * 那是「跨租户检索 + HTTP 200 零报错」的唯一可靠防线（陷阱 5）。
     */
    private List<GraphitiFact> doSearch(Long userId, String query, int maxFacts) {
        GraphitiSearchRequest request = new GraphitiSearchRequest(
                List.of(groupIdResolver.resolve(userId)), query, maxFacts);

        GraphitiSearchResponse response = execute("search", () -> recallClient.post()
                .uri(PATH_SEARCH)
                .body(request)
                .retrieve()
                .body(GraphitiSearchResponse.class));

        return response == null ? List.of() : response.facts();
    }

    /** 生效时刻：优先业务时间 validAt，为空时回退落库时刻 createdAt。 */
    private static OffsetDateTime effectiveAtOf(GraphitiFact fact) {
        return fact.validAt() != null ? fact.validAt() : fact.createdAt();
    }

    /**
     * 统一异常出口：把 Spring 的 HTTP 异常翻译成 {@link GraphitiClientException}，
     * 保留状态码与响应体片段供 B1 的 {@code GraphitiErrorClassifier} 分类。
     */
    private <T> T execute(String operation, Supplier<T> call) {
        try {
            return call.get();
        } catch (RestClientResponseException e) {
            // 非 2xx：状态码与 body 都要留下 —— 429/403 必须能与其他 4xx 区分（铁律 3）
            throw GraphitiClientException.of(operation, e.getStatusCode().value(), e.getResponseBodyAsString(), e);
        } catch (ResourceAccessException e) {
            // 连接失败 / 读超时：httpStatus 为 null
            throw GraphitiClientException.of(operation, null, null, e);
        } catch (RestClientException e) {
            throw GraphitiClientException.of(operation, null, null, e);
        }
    }

    /** 自建 ObjectMapper —— snake_case 下发 + OffsetDateTime 无损读写（陷阱 6）。 */
    private static ObjectMapper buildGraphitiObjectMapper() {
        return JsonMapper.builder()
                .addModule(new JavaTimeModule())
                // group_id / role_type / source_description / max_facts / invalid_at ... 全部由它下发
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                // 严禁写成时间戳：服务端要 ISO-8601
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                // 服务端字段多于本侧 DTO 属正常（/episodes 返回 EpisodicNode 原始序列化）
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
    }

    /**
     * 自建 RestClient：每档超时一个实例，且把 Jackson 转换器替换成本类的 mapper。
     */
    private static RestClient buildRestClient(String baseUrl, int connectMs, int readMs, ObjectMapper mapper) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(connectMs));
        factory.setReadTimeout(Duration.ofMillis(readMs));

        MappingJackson2HttpMessageConverter graphitiConverter = new MappingJackson2HttpMessageConverter(mapper);

        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .messageConverters(converters -> {
                    // 默认的 Jackson 转换器绑定的是全局 ObjectMapper，必须先摘掉再挂自己的
                    converters.removeIf(c -> c instanceof MappingJackson2HttpMessageConverter);
                    // ★ 必须插到【首位】，不能 append：RestClient 在未显式指定 Content-Type 时，
                    //   取「列表中第一个 canWrite 的转换器」；而 spring-web 的默认列表里还有
                    //   YamlHttpMessageConverter（snakeyaml 在 classpath 上就会注册），
                    //   它的 supports() 对任意类型都为 true → 追加到末尾会被 YAML 抢走，
                    //   请求体被序列化成 YAML 文档（首行 "---"），服务端 pydantic 直接 422。
                    //   这个坑已由 GraphitiContractTest 实测捕获，不是推测。
                    converters.add(0, graphitiConverter);
                })
                .build();
    }
}
