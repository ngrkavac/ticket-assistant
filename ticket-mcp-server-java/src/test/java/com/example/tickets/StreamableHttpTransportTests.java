package com.example.tickets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * The MCP transport (K6): Streamable HTTP at {@code /mcp} in its stateless mode, on the running
 * server and through the service-token filter (K5).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "mcp.service-token=s3cret-token")
@ActiveProfiles("local")
class StreamableHttpTransportTests {

  @Value("${local.server.port}")
  int port;

  private McpSyncClient client(String token) {
    HttpClientStreamableHttpTransport transport =
        HttpClientStreamableHttpTransport.builder("http://localhost:" + port)
            .endpoint("/mcp")
            .customizeRequest(request -> request.header("Authorization", "Bearer " + token))
            .build();
    return McpClient.sync(transport).requestTimeout(Duration.ofSeconds(10)).build();
  }

  @Test
  void aClientWithTheTokenInitializesListsAndCallsTools() {
    try (McpSyncClient client = client("s3cret-token")) {
      McpSchema.InitializeResult init = client.initialize();
      assertThat(init.serverInfo().name()).isEqualTo("ticket-server");
      assertThat(init.capabilities().tools()).isNotNull();

      assertThat(client.listTools().tools())
          .extracting(McpSchema.Tool::name)
          .containsExactlyInAnyOrder(
              "getTicket", "listOpenTickets", "listTickets", "listCustomers", "createTicket");

      McpSchema.CallToolResult result =
          client.callTool(new McpSchema.CallToolRequest("listCustomers", Map.of()));
      assertThat(result.isError()).isFalse();
      assertThat(((McpSchema.TextContent) result.content().get(0)).text()).startsWith("[");
    }
  }

  @Test
  void everyRequestStandsOnItsOwn() throws Exception {
    // Stateless: no Mcp-Session-Id, so any replica can answer any request, even without initialize.
    HttpResponse<String> init =
        post(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":"
                + "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
                + "\"clientInfo\":{\"name\":\"test\",\"version\":\"1.0.0\"}}}");
    assertThat(init.statusCode()).isEqualTo(200);
    assertThat(init.headers().firstValue("Content-Type"))
        .hasValueSatisfying(type -> assertThat(type).startsWith("application/json"));
    assertThat(init.headers().firstValue("Mcp-Session-Id")).isEmpty();

    HttpResponse<String> list = post("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}");
    assertThat(list.statusCode()).isEqualTo(200);
    assertThat(list.body()).contains("\"tools\"");

    // No server-to-client stream: the server never calls the client.
    HttpResponse<String> stream =
        HTTP.send(
            request().header("Accept", "text/event-stream").GET().build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(stream.statusCode()).isEqualTo(405);
  }

  private static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  private HttpRequest.Builder request() {
    return HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/mcp"))
        .timeout(Duration.ofSeconds(5))
        .header("Authorization", "Bearer s3cret-token");
  }

  private HttpResponse<String> post(String body) throws Exception {
    return HTTP.send(
        request()
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void aClientWithAWrongTokenCannotInitialize() {
    try (McpSyncClient client = client("wrong")) {
      assertThatThrownBy(client::initialize).isInstanceOf(RuntimeException.class);
    }
  }
}
