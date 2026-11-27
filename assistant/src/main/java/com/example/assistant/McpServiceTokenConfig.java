package com.example.assistant;

import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;
import java.util.HashMap;
import java.util.Map;
import org.springframework.ai.mcp.client.common.autoconfigure.properties.McpSseClientProperties;
import org.springframework.ai.mcp.client.common.autoconfigure.properties.McpStreamableHttpClientProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Service tokens for the MCP servers (T3.4, contract K5). The MCP client auto-configuration applies
 * the {@link McpSyncHttpClientRequestCustomizer} bean to every SSE and Streamable HTTP transport.
 * The assistant uses Streamable HTTP (K6); SSE connections are still matched, for local setups that
 * point a connection at a legacy SSE server.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(McpServiceTokenConfig.TokenProperties.class)
class McpServiceTokenConfig {

  /**
   * {@code helpdesk.mcp.tokens.<connection>}: the bearer token sent to the MCP server of that
   * {@code spring.ai.mcp.client.*.connections} entry; blank for none.
   */
  @ConfigurationProperties(prefix = "helpdesk.mcp")
  record TokenProperties(Map<String, String> tokens) {

    TokenProperties {
      tokens = tokens == null ? Map.of() : Map.copyOf(tokens);
    }
  }

  @Bean
  McpSyncHttpClientRequestCustomizer mcpServiceTokens(
      TokenProperties props,
      ObjectProvider<McpSseClientProperties> sse,
      ObjectProvider<McpStreamableHttpClientProperties> streamable) {
    Map<String, String> urls = new HashMap<>();
    sse.ifAvailable(p -> p.getConnections().forEach((name, c) -> urls.put(name, c.url())));
    streamable.ifAvailable(p -> p.getConnections().forEach((name, c) -> urls.put(name, c.url())));
    return new McpServiceTokens(urls, props.tokens());
  }
}
