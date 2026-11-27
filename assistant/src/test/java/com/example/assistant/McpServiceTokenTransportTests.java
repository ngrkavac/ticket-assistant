package com.example.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The real MCP Streamable HTTP client transport (K6) against an MCP server that checks the service
 * token like the ticket and request-log servers do (K5, stream B's {@code McpServiceAuthConfig}):
 * 401 without {@code Authorization: Bearer <token>}.
 */
class McpServiceTokenTransportTests {

  private static final String TOKEN = "stub-secret";

  private final TestMcpServer server =
      new TestMcpServer(true, TOKEN, Map.of("getTicket", "{\"id\":\"T-1001\"}")).start();

  @AfterEach
  void stopServer() {
    server.close();
  }

  private McpSyncClient client(Map<String, String> tokens) {
    var transport =
        HttpClientStreamableHttpTransport.builder(server.url())
            .endpoint("/mcp")
            .httpRequestCustomizer(new McpServiceTokens(Map.of("tickets", server.url()), tokens))
            .build();
    return McpClient.sync(transport).requestTimeout(Duration.ofSeconds(5)).build();
  }

  @Test
  void connectsToAServerThatRequiresTheToken() {
    try (McpSyncClient client = client(Map.of("tickets", TOKEN))) {
      client.initialize();
      assertThat(client.listTools().tools()).hasSize(1);
    }
    // initialize, the initialized notification, tools/list (POST), the event stream (GET) and
    // the end of the session (DELETE) all carried the token
    assertThat(server.authorizations)
        .hasSizeGreaterThanOrEqualTo(4)
        .containsOnly("Bearer " + TOKEN);
  }

  @Test
  void withoutTheTokenTheServerRefusesTheConnection() {
    try (McpSyncClient client = client(Map.of())) {
      assertThatThrownBy(client::initialize).isInstanceOf(RuntimeException.class);
    }
    assertThat(server.authorizations).contains("none").doesNotContain("Bearer " + TOKEN);
  }
}
