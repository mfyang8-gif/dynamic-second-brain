package com.yangmf.mini_nodepad.config;

import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import dev.langchain4j.service.tool.ToolProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Configuration
public class McpToolConfig {

    @Value("${mcp.server.search.base-url:}")
    private String searchMcpServerUrl;

    @Value("${mcp.aliyun.api-key:}")
    private String aliyunApiKey;

    @Bean
    public ToolProvider mcpToolProvider() {
        log.info("开始初始化 MCP 工具集合...");

        if (searchMcpServerUrl == null || searchMcpServerUrl.isBlank()) {
            log.warn("mcp.server.search.base-url 未配置，MCP 搜索工具将被跳过");
            return McpToolProvider.builder().mcpClients(List.of()).build();
        }
        if (aliyunApiKey == null || aliyunApiKey.isBlank()) {
            log.warn("mcp.aliyun.api-key 未配置，MCP 搜索工具将被跳过");
            return McpToolProvider.builder().mcpClients(List.of()).build();
        }

        List<McpClient> clients = new ArrayList<>();

        McpClient searchClient = buildMcpClient(
                "WebSearchMCPClient",
                searchMcpServerUrl,
                aliyunApiKey
        );
        if (searchClient != null) {
            clients.add(searchClient);
            log.info("WebSearch MCP 客户端注册成功");
        }

        if (clients.isEmpty()) {
            log.warn("所有 MCP 客户端均初始化失败，MCP 工具集为空（不影响核心功能）");
        }

        return McpToolProvider.builder()
                .mcpClients(clients)
                .filterToolNames("bailian_web_search")
                .build();
    }

    private McpClient buildMcpClient(String key, String url, String apiKey) {
        try {
            StreamableHttpMcpTransport transport = new StreamableHttpMcpTransport.Builder()
                    .url(url)
                    .customHeaders(Map.of("Authorization", "Bearer " + apiKey))
                    .logRequests(true)
                    .logResponses(true)
                    .build();

            return new DefaultMcpClient.Builder()
                    .key(key)
                    .transport(transport)
                    .autoHealthCheck(false)
                    .build();

        } catch (IllegalArgumentException e) {
            log.error("MCP 客户端 [{}] 参数非法: {}", key, e.getMessage());
        } catch (Exception e) {
            log.error("MCP 客户端 [{}] 初始化失败: {}", key, e.getMessage(), e);
        }
        return null;
    }

}