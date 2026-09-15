package com.yangmf.mini_nodepad.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "spring.data.qdrant")
public class QdrantProperties {
    private String host;
    private int port;
    private int httpPort = 6333;
    private boolean useTls;
    
    // 💡 这里从 Map 改成了 List，完美适配你的 YAML 结构
    private List<CollectionConfig> collections;

    @Data
    public static class CollectionConfig {
        private String name; // 👈 显式的集合名称
        private int dimension;
        private List<IndexConfig> indexes;
    }

    @Data
    public static class IndexConfig {
        private String name;
        private String type; 
    }
}