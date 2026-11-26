package com.example.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;
import io.modelcontextprotocol.common.McpTransportContext;
import java.net.URI;
import java.net.http.HttpRequest;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.client.common.autoconfigure.properties.McpSseClientProperties;
import org.springframework.ai.mcp.client.common.autoconfigure.properties.McpStreamableHttpClientProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class McpServiceTokensTests {

  private static final Map<String, String> URLS =
      Map.of(
          "tickets", "http://ticket-mcp-server:8081",
          "requestlog", "http://request-log-mcp-server:8082/");

  private static String header(McpSyncHttpClientRequestCustomizer customizer, String uri) {
    HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(uri));
    customizer.customize(builder, "POST", URI.create(uri), "{}", McpTransportContext.EMPTY);
    return builder.build().headers().firstValue("Authorization").orElse(null);
  }

  @Test
  void sendsEachServerItsOwnToken() {
    var tokens =
        new McpServiceTokens(URLS, Map.of("tickets", " t-secret ", "requestlog", "r-secret"));

    assertThat(header(tokens, "http://ticket-mcp-server:8081/sse")).isEqualTo("Bearer t-secret");
    assertThat(header(tokens, "http://TICKET-MCP-SERVER:8081/mcp/message?sessionId=1"))
        .isEqualTo("Bearer t-secret");
    assertThat(header(tokens, "http://request-log-mcp-server:8082/mcp"))
        .isEqualTo("Bearer r-secret");
  }

  @Test
  void neverSendsATokenToAnotherOrigin() {
    var tokens = new McpServiceTokens(URLS, Map.of("tickets", "t-secret"));

    assertThat(header(tokens, "http://ticket-mcp-server:9999/sse")).isNull();
    assertThat(header(tokens, "https://ticket-mcp-server:8081/sse")).isNull();
    assertThat(header(tokens, "http://evil.example:8081/sse")).isNull();
    assertThat(header(tokens, "http://request-log-mcp-server:8082/sse")).isNull();
    assertThat(tokens.tokenFor(null)).isNull();
    assertThat(tokens.tokenFor(URI.create("/relative"))).isNull();
  }

  @Test
  void aBlankOrMissingTokenSendsNoHeader() {
    Map<String, String> blank = new HashMap<>();
    blank.put("tickets", " ");
    blank.put("requestlog", null);
    var tokens = new McpServiceTokens(URLS, blank);

    assertThat(header(tokens, "http://ticket-mcp-server:8081/sse")).isNull();
    assertThat(header(tokens, "http://request-log-mcp-server:8082/sse")).isNull();
    assertThat(header(new McpServiceTokens(URLS, Map.of()), "http://ticket-mcp-server:8081/sse"))
        .isNull();
  }

  @Test
  void defaultPortsMatchExplicitOnes() {
    var tokens =
        new McpServiceTokens(
            Map.of("a", "https://mcp.example", "b", "http://other.example"),
            Map.of("a", "a-secret", "b", "b-secret"));

    assertThat(header(tokens, "https://mcp.example:443/mcp")).isEqualTo("Bearer a-secret");
    assertThat(header(tokens, "http://other.example:80/sse")).isEqualTo("Bearer b-secret");
  }

  @Test
  void aTokenWithoutAConnectionUrlIsNotUsed() {
    var tokens = new McpServiceTokens(Map.of(), Map.of("tickets", "t-secret"));
    assertThat(header(tokens, "http://ticket-mcp-server:8081/sse")).isNull();
  }

  @Test
  void rejectsTokensThatCannotBeSentAsAHeader() {
    assertThatThrownBy(() -> new McpServiceTokens(URLS, Map.of("tickets", "a b")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("tickets")
        .hasMessageNotContaining("a b");
    assertThatThrownBy(() -> new McpServiceTokens(URLS, Map.of("tickets", "a\r\nX: y")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsInvalidUrlsOfConnectionsWithAToken() {
    assertThatThrownBy(
            () -> new McpServiceTokens(Map.of("tickets", "not a url"), Map.of("tickets", "t")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("tickets");
    assertThatThrownBy(
            () -> new McpServiceTokens(Map.of("tickets", "localhost:8081"), Map.of("tickets", "t")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsDifferentTokensForTheSameOrigin() {
    Map<String, String> urls = Map.of("a", "http://mcp:8081", "b", "http://MCP:8081/other");
    assertThatThrownBy(() -> new McpServiceTokens(urls, Map.of("a", "x", "b", "y")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("same host and port");
    assertThat(
            header(new McpServiceTokens(urls, Map.of("a", "x", "b", "x")), "http://mcp:8081/sse"))
        .isEqualTo("Bearer x");
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties({
    McpSseClientProperties.class,
    McpStreamableHttpClientProperties.class
  })
  static class McpProperties {}

  @Test
  void theBeanTakesTheUrlsFromBothTransportsConnections() {
    new ApplicationContextRunner()
        .withUserConfiguration(McpServiceTokenConfig.class, McpProperties.class)
        .withPropertyValues(
            "spring.ai.mcp.client.sse.connections.tickets.url=http://tickets:8081",
            "spring.ai.mcp.client.streamable-http.connections.requestlog.url=http://log:8082",
            "helpdesk.mcp.tokens.tickets=t-secret",
            "helpdesk.mcp.tokens.requestlog=r-secret")
        .run(
            context -> {
              var customizer = context.getBean(McpSyncHttpClientRequestCustomizer.class);
              assertThat(header(customizer, "http://tickets:8081/sse"))
                  .isEqualTo("Bearer t-secret");
              assertThat(header(customizer, "http://log:8082/mcp")).isEqualTo("Bearer r-secret");
            });
  }

  @Test
  void withoutTokensTheBeanSendsNothing() {
    new ApplicationContextRunner()
        .withUserConfiguration(McpServiceTokenConfig.class)
        .run(
            context ->
                assertThat(
                        header(
                            context.getBean(McpSyncHttpClientRequestCustomizer.class),
                            "http://tickets:8081/sse"))
                    .isNull());
  }
}
