package com.yangmf.mini_nodepad.config;

import com.yangmf.mini_nodepad.properties.QdrantProperties;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.qdrant.QdrantEmbeddingStore;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Qdrant 向量数据库核心配置类 (基于 YAML 动态驱动)
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class QdrantConfig {

    // 注入我们刚才写好的 Properties 属性类
    private final QdrantProperties properties;

    /**
     * 初始化原生 QdrantClient (gRPC 通讯，用于后续的高性能增删改查)
     */
    @Bean
    public QdrantClient qdrantClient() {
        log.info("🔧 初始化原生 QdrantClient (gRPC): host={}, port={}", properties.getHost(), properties.getPort());
        return new QdrantClient(
                QdrantGrpcClient.newBuilder(
                        properties.getHost(),
                        properties.getPort(),
                        properties.isUseTls()).build()
        );
    }

    /**
     * 应用启动时，根据 YAML 动态初始化所有集合(Collections)和索引(Payload Indexes)
     */
    @PostConstruct
    public void initCollections() {
        if (properties.getCollections() == null || properties.getCollections().isEmpty()) {
            log.warn("⚠️ Qdrant 集合配置为空，跳过初始化。请检查 application.yml");
            return;
        }

        HttpClient client = HttpClient.newHttpClient();
        String baseUrl = String.format("http://%s:%d/collections/", properties.getHost(), properties.getHttpPort());

        // 遍历 YAML 中配置的所有集合
        for (QdrantProperties.CollectionConfig config : properties.getCollections()) {
            String collectionName = config.getName();
            if (collectionName == null || collectionName.trim().isEmpty()) {
                continue;
            }

            try {
                // 1. 检查并创建 Collection (支持双向量：稠密 + 稀疏)
                checkAndCreateCollection(client, baseUrl + collectionName, collectionName, config.getDimension());

                // 2. 动态创建 Payload 索引
                if (config.getIndexes() != null) {
                    for (QdrantProperties.IndexConfig index : config.getIndexes()) {
                        createPayloadIndex(client, baseUrl + collectionName, index.getName(), index.getType());
                    }
                }
            } catch (Exception e) {
                log.error(" 初始化 Qdrant 集合 [{}] 失败", collectionName, e);
            }
        }
    }

    /**
     * 辅助方法：检查并创建包含稠密和稀疏双向量的 Collection
     */
    private void checkAndCreateCollection(HttpClient client, String url, String name, int dimension) throws Exception {
        HttpResponse<Void> response = client.send(
                HttpRequest.newBuilder().uri(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.discarding()
        );

        if (response.statusCode() == 404) {
            log.info("🔧 集合 '{}' 不存在，正在创建双向量配置 (稠密维度: {})...", name, dimension);
            
            // 构建双向量配置 JSON
            String json = String.format("""
                {
                    "vectors": {
                        "size": %d,
                        "distance": "Cosine"
                    },
                    "sparse_vectors": {
                        "sparse": {}
                    }
                }
                """, dimension);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> createResponse = client.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (createResponse.statusCode() == 200 || createResponse.statusCode() == 201) {
                log.info(" Qdrant 集合 '{}' 创建成功", name);
            } else {
                log.error(" 创建集合 '{}' 失败: HTTP {} - {}", name, createResponse.statusCode(), createResponse.body());
            }
        } else if (response.statusCode() == 200) {
            log.info(" Qdrant 集合 '{}' 状态正常", name);
        }
    }

    /**
     * 辅助方法：下发 HTTP 请求建立 Payload 索引
     */
    private void createPayloadIndex(HttpClient client, String collectionUrl, String fieldName, String fieldSchema) throws Exception {
        String json = String.format("{\"field_name\": \"%s\", \"field_schema\": \"%s\"}", fieldName, fieldSchema);
        
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(collectionUrl + "/index"))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(json))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        
        if (response.statusCode() == 200 || response.statusCode() == 201) {
            log.info(" Qdrant 索引已确认: 字段='{}', 类型='{}'", fieldName, fieldSchema);
        } else {
            log.warn(" Qdrant 索引 [{}] 配置异常: HTTP {} - {}", fieldName, response.statusCode(), response.body());
        }
    }

}