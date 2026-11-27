package com.example.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.client.common.autoconfigure.properties.McpClientCommonProperties;
import org.springframework.ai.mcp.client.common.autoconfigure.properties.McpSseClientProperties;
import org.springframework.ai.mcp.client.common.autoconfigure.properties.McpStreamableHttpClientProperties;
import org.springframework.ai.mcp.client.common.autoconfigure.properties.McpStreamableHttpClientProperties.ConnectionParameters;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.PropertiesPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/** application.properties connects to the MCP servers over Streamable HTTP at /mcp (K6). */
class McpClientPropertiesTests {

  private static Binder binder(Map<String, Object> env) throws IOException {
    StandardEnvironment environment = new StandardEnvironment();
    environment.getPropertySources().addFirst(new MapPropertySource("env", env));
    new PropertiesPropertySourceLoader()
        .load("application", new ClassPathResource("application.properties"))
        .forEach(environment.getPropertySources()::addLast);
    return Binder.get(environment);
  }

  private static Map<String, ConnectionParameters> connections(Map<String, Object> env)
      throws IOException {
    return binder(env)
        .bind(
            McpStreamableHttpClientProperties.CONFIG_PREFIX,
            McpStreamableHttpClientProperties.class)
        .get()
        .getConnections();
  }

  @Test
  void bothServersAreReachedOverStreamableHttpAtMcp() throws IOException {
    assertThat(connections(Map.of()))
        .containsOnly(
            Map.entry("tickets", new ConnectionParameters("http://localhost:8081", "/mcp")),
            Map.entry("requestlog", new ConnectionParameters("http://localhost:8082", "/mcp")));
    assertThat(
            binder(Map.of())
                .bind(McpSseClientProperties.CONFIG_PREFIX, McpSseClientProperties.class)
                .map(McpSseClientProperties::getConnections)
                .orElse(Map.of()))
        .isEmpty();
  }

  @Test
  void theUrlsAreBaseUrlsFromTheEnvironment() throws IOException {
    // the compose and Helm values keep setting base URLs; /mcp is appended by the transport
    assertThat(
            connections(
                Map.of(
                    "TICKETS_MCP_URL", "http://ticket-mcp-server:8081",
                    "REQUEST_LOG_MCP_URL", "http://request-log-mcp-server:8082")))
        .containsOnly(
            Map.entry("tickets", new ConnectionParameters("http://ticket-mcp-server:8081", "/mcp")),
            Map.entry(
                "requestlog",
                new ConnectionParameters("http://request-log-mcp-server:8082", "/mcp")));
  }

  @Test
  void theClientsConnectLazily() throws IOException {
    Binder binder = binder(Map.of());
    McpClientCommonProperties common =
        binder.bind(McpClientCommonProperties.CONFIG_PREFIX, McpClientCommonProperties.class).get();
    assertThat(common.isInitialized()).isFalse();
    assertThat(binder.bind("spring.ai.mcp.client.toolcallback.enabled", Boolean.class).orElse(true))
        .isFalse();
  }
}
