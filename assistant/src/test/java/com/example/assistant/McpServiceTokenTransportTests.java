package com.example.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The real MCP SSE client transport against a stub MCP server that checks the service token like
 * the ticket and request-log servers do (K5, stream B's {@code McpServiceAuthConfig}): 401 without
 * {@code Authorization: Bearer <token>}.
 */
class McpServiceTokenTransportTests {

  private static final String TOKEN = "stub-secret";

  private static final ObjectMapper JSON = new ObjectMapper();

  private HttpServer server;

  private String url;

  /** The Authorization header of every request the stub got ("none" when missing). */
  private final List<String> authorizations = new CopyOnWriteArrayList<>();

  private volatile OutputStream events;

  private final CountDownLatch stop = new CountDownLatch(1);

  @BeforeEach
  void startStub() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setExecutor(Executors.newCachedThreadPool());
    server.createContext("/sse", this::sse);
    server.createContext("/mcp/message", this::message);
    server.start();
    url = "http://127.0.0.1:" + server.getAddress().getPort();
  }

  @AfterEach
  void stopStub() {
    stop.countDown();
    server.stop(0);
  }

  private boolean authorized(HttpExchange exchange) throws IOException {
    String auth = exchange.getRequestHeaders().getFirst("Authorization");
    authorizations.add(auth == null ? "none" : auth);
    if (("Bearer " + TOKEN).equals(auth)) {
      return true;
    }
    exchange.getResponseHeaders().add("WWW-Authenticate", "Bearer");
    exchange.sendResponseHeaders(401, -1);
    exchange.close();
    return false;
  }

  private void sse(HttpExchange exchange) throws IOException {
    if (!authorized(exchange)) {
      return;
    }
    exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
    exchange.sendResponseHeaders(200, 0);
    OutputStream out = exchange.getResponseBody();
    send(out, "endpoint", "/mcp/message?sessionId=s1");
    events = out;
    try {
      stop.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    exchange.close();
  }

  private void message(HttpExchange exchange) throws IOException {
    if (!authorized(exchange)) {
      return;
    }
    JsonNode request = JSON.readTree(exchange.getRequestBody());
    exchange.sendResponseHeaders(200, -1);
    exchange.close();
    if (!request.has("id")) {
      return; // a notification
    }
    Object result =
        switch (request.path("method").asText()) {
          case "initialize" ->
              Map.of(
                  "protocolVersion", request.path("params").path("protocolVersion").asText(),
                  "capabilities", Map.of("tools", Map.of()),
                  "serverInfo", Map.of("name", "stub", "version", "1"));
          case "tools/list" -> Map.of("tools", List.of());
          default -> Map.of();
        };
    send(
        events,
        "message",
        JSON.writeValueAsString(
            Map.of("jsonrpc", "2.0", "id", request.get("id"), "result", result)));
  }

  private static synchronized void send(OutputStream out, String event, String data)
      throws IOException {
    out.write(("event: " + event + "\ndata: " + data + "\n\n").getBytes(StandardCharsets.UTF_8));
    out.flush();
  }

  private McpSyncClient client(Map<String, String> tokens) {
    var transport =
        HttpClientSseClientTransport.builder(url)
            .httpRequestCustomizer(new McpServiceTokens(Map.of("tickets", url), tokens))
            .build();
    return McpClient.sync(transport).requestTimeout(Duration.ofSeconds(5)).build();
  }

  @Test
  void connectsToAServerThatRequiresTheToken() {
    try (McpSyncClient client = client(Map.of("tickets", TOKEN))) {
      client.initialize();
      assertThat(client.listTools().tools()).isEmpty();
    }
    // the SSE stream (GET) and every message (POST) carried the token
    assertThat(authorizations).hasSizeGreaterThanOrEqualTo(4).containsOnly("Bearer " + TOKEN);
  }

  @Test
  void withoutTheTokenTheServerRefusesTheConnection() {
    try (McpSyncClient client = client(Map.of())) {
      assertThatThrownBy(client::initialize).isInstanceOf(RuntimeException.class);
    }
    assertThat(authorizations).contains("none").doesNotContain("Bearer " + TOKEN);
  }
}
