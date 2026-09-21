package com.yangmf.mini_nodepad.model;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.scoring.ScoringModel;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.util.EntityUtils;
import org.springframework.beans.factory.annotation.Value;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Slf4j
public class DashScopeScoringModel implements ScoringModel, AutoCloseable {

    private final String modelName;
    private final String apiKey;
    private final String baseUrl;
    private final Gson gson = new Gson();
    
    // 【核心优化】保持全局唯一的 HttpClient，利用连接池复用 TCP 连接
    private final CloseableHttpClient httpClient;

    @Builder
    public DashScopeScoringModel( String modelName, String apiKey,  String baseUrl, Integer timeoutMs) {
        this.modelName = modelName != null ? modelName : "gte-rerank-v2";
        this.apiKey = apiKey;
        this.baseUrl = baseUrl != null ? baseUrl : "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank";
        int timeout = timeoutMs != null ? timeoutMs : 15000;

        // 配置连接池（生产环境必备）
        PoolingHttpClientConnectionManager connManager = new PoolingHttpClientConnectionManager();
        connManager.setMaxTotal(50); // 最大连接数
        connManager.setDefaultMaxPerRoute(20); // 单个路由最大连接数

        RequestConfig config = RequestConfig.custom()
                .setConnectTimeout(timeout)
                .setSocketTimeout(timeout)
                .setConnectionRequestTimeout(5000)
                .build();

        this.httpClient = HttpClients.custom()
                .setConnectionManager(connManager)
                .setDefaultRequestConfig(config)
                .build();
    }

    @Override
    public Response<List<Double>> scoreAll(List<TextSegment> segments, String query) {
        // 【核心优化】短路防御，如果为空直接返回，不浪费网络请求
        if (segments == null || segments.isEmpty()) {
            return Response.from(Collections.emptyList());
        }

        long start = System.currentTimeMillis();

        try {
            JsonObject requestBody = new JsonObject();
            requestBody.addProperty("model", modelName);

            JsonObject input = new JsonObject();
            input.addProperty("query", query);
            JsonArray documents = new JsonArray();
            for (TextSegment segment : segments) {
                documents.add(segment.text());
            }
            input.add("documents", documents);
            requestBody.add("input", input);

            JsonObject parameters = new JsonObject();
            parameters.addProperty("return_documents", false);
            requestBody.add("parameters", parameters);

            HttpPost post = new HttpPost(baseUrl);
            post.setHeader("Content-Type", "application/json");
            post.setHeader("Authorization", "Bearer " + apiKey);
            post.setEntity(new StringEntity(gson.toJson(requestBody), StandardCharsets.UTF_8));

            // 直接使用全局 HttpClient
            try (CloseableHttpResponse resp = httpClient.execute(post)) {
                int status = resp.getStatusLine().getStatusCode();
                String body = EntityUtils.toString(resp.getEntity(), StandardCharsets.UTF_8);

                if (status != 200) {
                    throw new RuntimeException("DashScope Rerank API 异常 | status=" + status + ", body=" + body);
                }

                Response<List<Double>> result = parseResponse(body, segments.size());
                log.info("DashScope Rerank 完成 | model={}, docs={}, cost={}ms",
                        modelName, segments.size(), System.currentTimeMillis() - start);
                return result;
            }
        } catch (Exception e) {
            log.error("DashScope Rerank 批量打分失败 | query={}", query, e);
            throw new RuntimeException("DashScope Rerank failed", e);
        }
    }

    private Response<List<Double>> parseResponse(String responseBody, int expectedSize) {
        JsonObject json = gson.fromJson(responseBody, JsonObject.class);
        JsonObject output = json.getAsJsonObject("output");
        if (output == null) {
            throw new RuntimeException("DashScope Rerank 响应缺少 output 字段 | response=" + responseBody);
        }

        JsonArray results = output.getAsJsonArray("results");
        if (results == null) {
            throw new RuntimeException("DashScope Rerank 响应缺少 results 字段 | response=" + responseBody);
        }

        // 使用 double 数组确保顺序一致性
        double[] scores = new double[expectedSize];
        for (JsonElement element : results) {
            JsonObject r = element.getAsJsonObject();
            int index = r.get("index").getAsInt();
            double score = r.get("relevance_score").getAsDouble();
            if (index >= 0 && index < expectedSize) {
                scores[index] = score;
            }
        }

        List<Double> scoreList = new ArrayList<>(expectedSize);
        for (double s : scores) {
            scoreList.add(s);
        }
        return Response.from(scoreList);
    }
    
    // 实现 AutoCloseable 优雅关闭资源
    @Override
    public void close() throws IOException {
        if (httpClient != null) {
            httpClient.close();
        }
    }
}