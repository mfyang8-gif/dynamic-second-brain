package com.yangmf.mini_nodepad.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yangmf.mini_nodepad.exception.BusinessException;
import com.yangmf.mini_nodepad.exception.ValidationException;
import com.yangmf.mini_nodepad.pojo.vo.WebSearchResultVO;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.scoring.ScoringModel;
import dev.langchain4j.service.tool.ToolExecutionResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class WebSearchService {

    private static final String WEB_SEARCH_TOOL = "bailian_web_search";
    private static final int MAX_PER_DOMAIN = 2;

    private final ObjectProvider<McpClient> webSearchMcpClientProvider;
    private final ObjectMapper objectMapper;
    private final ScoringModel scoringModel;

    @Value("${web-search.default-limit:6}")
    private int defaultLimit;

    @Value("${web-search.max-limit:10}")
    private int maxLimit;

    @Value("${web-search.recall-count:18}")
    private int recallCount;

    public WebSearchService(ObjectProvider<McpClient> webSearchMcpClientProvider,
                            ObjectMapper objectMapper,
                            ScoringModel scoringModel) {
        this.webSearchMcpClientProvider = webSearchMcpClientProvider;
        this.objectMapper = objectMapper;
        this.scoringModel = scoringModel;
    }

    public List<WebSearchResultVO> query(String keyword, Integer limit) {
        if (keyword == null || keyword.isBlank()) {
            throw new ValidationException("搜索关键词不能为空");
        }
        int topN = (limit == null || limit <= 0) ? defaultLimit : Math.min(limit, maxLimit);

        // ① MCP 粗召回（纯代码，无大模型）
        List<WebSearchResultVO> raw = recallFromMcp(keyword.trim());
        if (raw.isEmpty()) {
            return List.of();
        }

        // ② 域名打散（同域名最多 2 条）→ ③ Rerank 精排 → ④ 截取 topN
        List<WebSearchResultVO> diversified = diversifyByDomain(raw);
        List<WebSearchResultVO> finalList = rerankAndTruncate(keyword.trim(), diversified, topN);
        log.info("联网搜索漏斗 | keyword={}, recall={}, diversified={}, final={}",
                keyword, raw.size(), diversified.size(), finalList.size());
        return finalList;
    }

    // ==================== ① MCP 直连召回 ====================

    private List<WebSearchResultVO> recallFromMcp(String keyword) {
        McpClient mcpClient = webSearchMcpClientProvider.getIfAvailable();
        if (mcpClient == null) {
            throw new BusinessException("联网搜索未启用：WebSearch MCP 未配置");
        }

        try {
            String arguments = objectMapper.writeValueAsString(
                    Map.of("query", keyword, "count", recallCount));

            ToolExecutionRequest request = ToolExecutionRequest.builder()
                    .name(WEB_SEARCH_TOOL)
                    .arguments(arguments)
                    .build();

            ToolExecutionResult execResult = mcpClient.executeTool(request);
            if (execResult == null || execResult.isError()) {
                throw new BusinessException("联网搜索工具执行失败");
            }

            List<WebSearchResultVO> results = parsePages(extractResultText(execResult));
            log.info("MCP 联网搜索召回 | keyword={}, count={}, parsed={}", keyword, recallCount, results.size());
            return results;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("MCP 联网搜索召回异常 | keyword={}", keyword, e);
            throw new BusinessException("联网搜索失败，请稍后重试", e);
        }
    }

    private String extractResultText(ToolExecutionResult r) {
        try {
            return r.resultText();
        } catch (IllegalStateException notSingleText) {
            StringBuilder sb = new StringBuilder();
            for (Content c : r.resultContents()) {
                if (c instanceof TextContent tc) {
                    sb.append(tc.text());
                }
            }
            return sb.toString();
        }
    }

    // ==================== 纯代码反序列化 ====================

    private List<WebSearchResultVO> parsePages(String json) throws Exception {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        JsonNode root = objectMapper.readTree(json);

        JsonNode array = null;
        if (root.isArray()) {
            array = root;
        } else {
            for (String field : new String[]{"pages", "results", "items", "webPages", "data"}) {
                JsonNode node = root.get(field);
                if (node != null && node.isArray()) {
                    array = node;
                    break;
                }
            }
        }
        if (array == null) {
            return List.of();
        }

        List<WebSearchResultVO> list = new ArrayList<>();
        for (JsonNode n : array) {
            String title = firstText(n, "title", "name");
            String url = firstText(n, "url", "link", "href");
            String snippet = firstText(n, "snippet", "content", "description", "summary", "text");
            if (url == null && title == null && snippet == null) {
                continue;
            }
            WebSearchResultVO vo = new WebSearchResultVO(title, url, snippet);
            // 发布时间：2026-10-12 补 —— 上游字段名未知，做多候选探测；
            // 全部落空即保持 null，由上下文层退化为「本轮实时检索」标注。
            vo.setPublishedTime(firstText(n, "published_time", "publishedTime", "publish_time",
                    "publishTime", "publish_date", "publishDate", "pubTime", "datePublished",
                    "published_at", "publishedAt", "pub_time", "post_time", "timestamp"));
            list.add(vo);
        }
        return list;
    }

    private String firstText(JsonNode node, String... fields) {
        for (String f : fields) {
            JsonNode v = node.get(f);
            if (v != null && v.isValueNode() && !v.asText().isBlank()) {
                return v.asText();
            }
        }
        return null;
    }

    // ==================== ② 域名打散 ====================

    private List<WebSearchResultVO> diversifyByDomain(List<WebSearchResultVO> raw) {
        Map<String, Integer> hostCount = new HashMap<>();
        return raw.stream()
                .filter(r -> r.getUrl() != null && !r.getUrl().isBlank())
                .filter(r -> {
                    String host = extractHost(r.getUrl());
                    if (host == null || host.isBlank()) {
                        host = "raw:" + r.getUrl();
                    }
                    int c = hostCount.getOrDefault(host, 0);
                    if (c >= MAX_PER_DOMAIN) {
                        return false;
                    }
                    hostCount.put(host, c + 1);
                    return true;
                })
                .toList();
    }

    // ==================== ③ Rerank 精排 + ④ 截取 ====================

    private List<WebSearchResultVO> rerankAndTruncate(String query, List<WebSearchResultVO> candidates, int topN) {
        if (candidates.size() <= 1) {
            return candidates.stream().limit(topN).toList();
        }

        List<TextSegment> segments = candidates.stream()
                .map(c -> TextSegment.from(c.getSnippet() != null ? c.getSnippet() : ""))
                .toList();

        long start = System.currentTimeMillis();
        try {
            Response<List<Double>> response = scoringModel.scoreAll(segments, query);
            long cost = System.currentTimeMillis() - start;
            List<Double> scores = response.content();

            List<ScoredItem> scored = new ArrayList<>();
            for (int i = 0; i < candidates.size(); i++) {
                double score = (scores != null && i < scores.size()) ? scores.get(i) : 0.0;
                scored.add(new ScoredItem(candidates.get(i), score));
            }

            List<WebSearchResultVO> top = scored.stream()
                    .sorted(Comparator.comparingDouble(ScoredItem::score).reversed())
                    .limit(topN)
                    .map(ScoredItem::item)
                    .toList();

            log.info("联网搜索 Rerank 完成 | keyword={}, input={}, output={}, cost={}ms",
                    query, candidates.size(), top.size(), cost);
            return top;
        } catch (Exception e) {
            long cost = System.currentTimeMillis() - start;
            log.warn("联网搜索 Rerank 失败，降级使用召回顺序 | keyword={}, cost={}ms", query, cost, e);
            return candidates.stream().limit(topN).toList();
        }
    }
    private String extractHost(String url) {
        try {
            String normalized = url.startsWith("http") ? url : "http://" + url;
            String host = URI.create(normalized).getHost();
            if (host == null) {
                return null;
            }
            host = host.toLowerCase();
            return host.startsWith("www.") ? host.substring(4) : host;
        } catch (Exception e) {
            return null;
        }
    }

    private record ScoredItem(WebSearchResultVO item, double score) {}
}