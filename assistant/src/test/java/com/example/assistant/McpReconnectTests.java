package com.example.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.modelcontextprotocol.client.McpSyncClient;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.mcp.McpToolFilter;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.mcp.client.common.autoconfigure.McpClientAutoConfiguration;
import org.springframework.ai.mcp.client.httpclient.autoconfigure.StreamableHttpHttpClientTransportAutoConfiguration;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * The assistant's MCP wiring (Spring AI's Streamable HTTP client auto-configuration, the service
 * tokens, {@link TicketDirectoryClient}, {@link RequestLogClient} and the model's tools) against
 * two in-process MCP servers that are stopped and restarted (T3.1, T3.2). It runs against stateless
 * servers, as the ticket and request-log servers are since B10 ({@link
 * StatelessMcpReconnectTests}), and against stateful ones, whose sessions a restart loses ({@link
 * StatefulMcpReconnectTests}).
 */
abstract class McpReconnectTests {

  private static final String TICKETS_TOKEN = "tickets-secret";

  private static final String REQUEST_LOG_TOKEN = "request-log-secret";

  private static final HelpdeskPrincipal ACME_WRITER =
      new HelpdeskPrincipal(
          "sub-1",
          "acme-writer",
          Set.of(SecurityConfig.ROLE_AGENT, SecurityConfig.ROLE_WRITER),
          Set.of("acme"),
          false);

  private final boolean stateless;

  private final TestMcpServer tickets;

  private final TestMcpServer requestLog;

  McpReconnectTests(boolean stateless) {
    this.stateless = stateless;
    this.tickets =
        new TestMcpServer(
            stateless,
            TICKETS_TOKEN,
            Map.of(
                "listCustomers", "[\"acme\",\"globex\"]",
                "getTicket", "{\"id\":\"T-1001\",\"customer\":\"acme\"}",
                "createTicket", "{\"id\":\"T-2001\",\"customer\":\"acme\"}"));
    this.requestLog =
        new TestMcpServer(
            stateless,
            REQUEST_LOG_TOKEN,
            Map.of(
                "logRequest", "{\"stored\":true}",
                "listQuestions", "[{\"id\":1,\"text\":\"How do I reset my password?\"}]"));
  }

  @AfterEach
  void stopServers() {
    tickets.close();
    requestLog.close();
  }

  /** The beans of the assistant that talk to the MCP servers. */
  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(HelpdeskProperties.class)
  @Import({McpServiceTokenConfig.class, TicketDirectoryClient.class, RequestLogClient.class})
  static class Wiring {

    @Bean
    MeterRegistry meters() {
      return new SimpleMeterRegistry();
    }

    @Bean
    McpToolFilter hideInternalTools(HelpdeskProperties props) {
      return new ChatConfig().hideInternalTools(props);
    }

    @Bean
    SyncMcpToolCallbackProvider mcpToolCallbacks(
        ObjectProvider<List<McpSyncClient>> clients,
        McpToolFilter filter,
        HelpdeskProperties props) {
      return new ChatConfig().mcpToolCallbacks(clients, filter, props);
    }

    @Bean
    ModelTools modelTools(SyncMcpToolCallbackProvider tools, MeterRegistry meters) {
      return new ChatConfig().modelTools(tools, meters);
    }
  }

  /** The assistant's MCP settings from application.properties, pointed at the test servers. */
  private void run(Consumer<AssertableApplicationContext> test) {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                StreamableHttpHttpClientTransportAutoConfiguration.class,
                McpClientAutoConfiguration.class))
        .withUserConfiguration(Wiring.class)
        .withPropertyValues(
            "spring.ai.mcp.client.streamable-http.connections.tickets.url=" + tickets.url(),
            "spring.ai.mcp.client.streamable-http.connections.tickets.endpoint=/mcp",
            "spring.ai.mcp.client.streamable-http.connections.requestlog.url=" + requestLog.url(),
            "spring.ai.mcp.client.streamable-http.connections.requestlog.endpoint=/mcp",
            "spring.ai.mcp.client.initialized=false",
            "spring.ai.mcp.client.request-timeout=5s",
            "helpdesk.mcp.tokens.tickets=" + TICKETS_TOKEN,
            "helpdesk.mcp.tokens.requestlog=" + REQUEST_LOG_TOKEN,
            "helpdesk.customers-cache-ttl=0s")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              test.accept(context);
            });
  }

  private static ToolContext toolContext() {
    return new ToolContext(
        Map.of(
            CustomerScopedToolCallback.CUSTOMER,
            "acme",
            CustomerScopedToolCallback.PRINCIPAL,
            ACME_WRITER));
  }

  private static CustomerScopedToolCallback tool(
      AssertableApplicationContext context, String name) {
    return context.getBean(ModelTools.class).all().stream()
        .filter(t -> t.getToolDefinition().name().equals(name))
        .findFirst()
        .orElseThrow();
  }

  private static void allWork(AssertableApplicationContext context) {
    assertThat(context.getBean(TicketDirectoryClient.class).customers())
        .containsExactly("acme", "globex");
    assertThat(tool(context, "getTicket").call("{\"id\":\"T-1001\"}", toolContext()))
        .contains("T-1001");
    assertThat(context.getBean(RequestLogClient.class).questions())
        .extracting(RequestLogClient.CatalogQuestion::text)
        .containsExactly("How do I reset my password?");
  }

  @Test
  void startsWithTheServersDownAndConnectsOnceTheyAreUp() {
    run(
        context -> {
          // the context started without any server: nothing connected at startup
          assertThat(tickets.initializations).hasValue(0);
          assertThatThrownBy(() -> context.getBean(TicketDirectoryClient.class).customers())
              .isInstanceOf(TicketsUnavailableException.class);
          assertThatThrownBy(() -> context.getBean(ModelTools.class).all())
              .isInstanceOf(TicketsUnavailableException.class);
          assertThatThrownBy(() -> context.getBean(RequestLogClient.class).questions())
              .isInstanceOf(RequestLogUnavailableException.class);

          tickets.start();
          requestLog.start();

          allWork(context);
        });
  }

  @Test
  void recoversWhenTheServersRestartWithoutFailingACall() {
    tickets.start();
    requestLog.start();
    run(
        context -> {
          allWork(context);
          @SuppressWarnings("unchecked")
          List<McpSyncClient> clients = (List<McpSyncClient>) context.getBean("mcpSyncClients");

          tickets.restart();
          requestLog.restart();

          // every call still succeeds with the same clients; a stateful server's old sessions are
          // gone, so the clients opened new ones
          allWork(context);
          assertThat(context.getBean("mcpSyncClients")).isEqualTo(clients);
          if (!stateless) {
            assertThat(tickets.initializations).hasValue(2);
            assertThat(requestLog.initializations).hasValue(2);
          }
        });
  }

  @Test
  void recoversWhenAServerWasDownForAWhile() {
    tickets.start();
    requestLog.start();
    run(
        context -> {
          allWork(context);
          tickets.stop();
          assertThatThrownBy(() -> context.getBean(TicketDirectoryClient.class).customers())
              .isInstanceOf(TicketsUnavailableException.class);
          tickets.start();
          allWork(context);
        });
  }

  @Test
  void sendsEachServerItsOwnServiceToken() {
    tickets.start();
    requestLog.start();
    run(
        context -> {
          allWork(context);
          tickets.restart();
          requestLog.restart();
          allWork(context);
        });
    // initialize, notifications, the GET event stream, tool calls (and, with sessions, the
    // DELETE at shutdown)
    assertThat(tickets.authorizations)
        .hasSizeGreaterThanOrEqualTo(4)
        .containsOnly("Bearer " + TICKETS_TOKEN);
    assertThat(requestLog.authorizations)
        .hasSizeGreaterThanOrEqualTo(4)
        .containsOnly("Bearer " + REQUEST_LOG_TOKEN);
  }

  @Test
  void createTicketRunsAtMostOnceAfterARestart() {
    tickets.start();
    run(
        context -> {
          CustomerScopedToolCallback createTicket = tool(context, "createTicket");
          tickets.restart();

          if (stateless) {
            // nothing to recover: the call simply goes to the new server
            assertThat(createTicket.call("{\"title\":\"VPN down\"}", toolContext()))
                .contains("T-2001");
            assertThat(tickets.calls("createTicket")).isEqualTo(1);
            return;
          }
          // the restarted server refused the call (unknown session) and it is not repeated
          assertThatThrownBy(() -> createTicket.call("{\"title\":\"VPN down\"}", toolContext()))
              .isInstanceOf(ToolExecutionException.class);
          assertThat(tickets.calls("createTicket")).isZero();

          // the next call runs on a new session
          assertThat(createTicket.call("{\"title\":\"VPN down\"}", toolContext()))
              .contains("T-2001");
          assertThat(tickets.calls("createTicket")).isEqualTo(1);
        });
  }

  @Test
  void theModelGetsOnlyTheTicketToolsAndTheyWorkWhileTheRequestLogIsDown() {
    tickets.start();
    run(
        context -> {
          assertThat(context.getBean(ModelTools.class).all())
              .extracting(t -> t.getToolDefinition().name())
              .containsExactlyInAnyOrder("getTicket", "createTicket");
          assertThat(tool(context, "getTicket").call("{\"id\":\"T-1001\"}", toolContext()))
              .contains("T-1001");
          assertThat(requestLog.authorizations).isEmpty(); // never contacted
        });
  }
}
