package com.example.tickets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.ActiveProfiles;

/** Service token and Origin check on the MCP endpoints (K5), against the running server. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "mcp.service-token=s3cret-token",
      "mcp.allowed-origins=http://localhost:6274, https://inspector.example"
    })
@ActiveProfiles("local")
class McpServiceAuthTests {

  private static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  @Value("${local.server.port}")
  int port;

  private HttpResponse<String> post(String path, String... headers) throws Exception {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .timeout(Duration.ofSeconds(5))
            .POST(HttpRequest.BodyPublishers.ofString("{}"))
            .header("Content-Type", "application/json");
    if (headers.length > 0) {
      request.headers(headers);
    }
    return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> get(String path, String... headers)
      throws IOException, InterruptedException {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .timeout(Duration.ofSeconds(5));
    if (headers.length > 0) {
      request.headers(headers);
    }
    return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void requestsWithoutTheTokenGet401() throws Exception {
    HttpResponse<String> missing = get("/sse");
    assertThat(missing.statusCode()).isEqualTo(401);
    assertThat(missing.headers().firstValue("WWW-Authenticate")).hasValue("Bearer");

    assertThat(post("/mcp/message", "Authorization", "Bearer wrong").statusCode()).isEqualTo(401);
    assertThat(post("/mcp/message", "Authorization", "Basic s3cret-token").statusCode())
        .isEqualTo(401);
    assertThat(post("/mcp/message", "Authorization", "Bearer s3cret-token-and-more").statusCode())
        .isEqualTo(401);
  }

  @Test
  void requestsWithTheTokenReachTheMcpEndpoints() throws Exception {
    // No session id: the MCP transport itself rejects the message, after the filter let it pass.
    HttpResponse<String> response = post("/mcp/message", "Authorization", "Bearer s3cret-token");
    assertThat(response.statusCode()).isNotIn(401, 403).isLessThan(500);
    assertThat(post("/mcp/message", "Authorization", "bearer s3cret-token").statusCode())
        .isNotIn(401, 403);
  }

  @Test
  void browserOriginsAreRejectedEvenWithTheToken() throws Exception {
    assertThat(
            post(
                    "/mcp/message",
                    "Authorization",
                    "Bearer s3cret-token",
                    "Origin",
                    "https://evil.example")
                .statusCode())
        .isEqualTo(403);
    assertThat(post("/mcp/message", "Origin", "null").statusCode()).isEqualTo(403);
  }

  @Test
  void allowedOriginsStillNeedTheToken() throws Exception {
    assertThat(post("/mcp/message", "Origin", "https://inspector.example").statusCode())
        .isEqualTo(401);
    assertThat(
            post(
                    "/mcp/message",
                    "Origin",
                    "http://localhost:6274",
                    "Authorization",
                    "Bearer s3cret-token")
                .statusCode())
        .isNotIn(401, 403);
  }

  @Test
  void probesNeedNoToken() throws Exception {
    assertThat(get("/actuator/health/readiness").statusCode()).isEqualTo(200);
    assertThat(get("/actuator/health/liveness").statusCode()).isEqualTo(200);
  }

  @Test
  void prodProfileRequiresAToken() {
    var config = new McpServiceAuthConfig();
    var prod = new MockEnvironment();
    prod.setActiveProfiles("prod");
    assertThatThrownBy(() -> config.mcpServiceAuthFilter(" ", "", prod))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("MCP_SERVICE_TOKEN must be set in the prod profile");

    var local = new MockEnvironment();
    local.setActiveProfiles("local");
    assertThat(config.mcpServiceAuthFilter("", "", local).getFilter()).isNotNull();
    assertThat(config.mcpServiceAuthFilter("token", "", prod).getFilter()).isNotNull();
  }

  @Test
  void originListIsTrimmedAndIgnoresEmptyEntries() {
    assertThat(McpServiceAuthConfig.parseOrigins(" https://a.example , ,http://b.example:8080,"))
        .containsExactlyInAnyOrder("https://a.example", "http://b.example:8080");
    assertThat(McpServiceAuthConfig.parseOrigins("")).isEmpty();
  }
}
