package com.yangmf.mini_nodepad.ai.config;

import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

@Slf4j
@Configuration
public class McpToolConfig {

    /**
     * WebSearch MCP 客户端单例：仅当配置了 mcp.server.search.base-url 时才实例化。
     * 配置缺失则整个 Bean 不注册（而非返回 null），Spring 生命周期与 close() 交由容器托管。
     * 复用方：WebSearchService（纯代码直连 executeTool）。
     *
     * <p>2026-10-12 起不再向模型挂 ToolProvider（原 mcpToolProvider Bean 已随
     * 主模型解绑一并移除）—— 联网一律走「预检索注入」，见 WebAugmentService。
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "mcp.server.search.base-url")
    public McpClient webSearchMcpClient(
            @Value("${mcp.server.search.base-url}") String url,
            @Value("${mcp.aliyun.api-key:}") String apiKey) {

        log.info("初始化 WebSearch MCP 客户端 | url={}", url);
        StreamableHttpMcpTransport transport = new StreamableHttpMcpTransport.Builder()
                .url(url)
                .customHeaders(Map.of("Authorization", "Bearer " + apiKey))
                .logRequests(true)
                .logResponses(true)
                .build();

        return new DefaultMcpClient.Builder()
                .key("WebSearchMCPClient")
                .transport(transport)
                .autoHealthCheck(false)
                .build();
    }
}