package com.example.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.McpConnectionInfo;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.beans.factory.ObjectProvider;

class ChatConfigTests {

  private static ToolExecutionException failure() {
    ToolDefinition tool =
        ToolDefinition.builder().name("getTicket").description("d").inputSchema("{}").build();
    return new ToolExecutionException(tool, new IllegalStateException("Ticket T-1234 not found"));
  }

  @Test
  void toolFailureIsReturnedToTheModel() {
    var processor = new ChatConfig().toolExecutionExceptionProcessor(false);
    assertThat(processor.process(failure())).contains("Ticket T-1234 not found");
  }

  @Test
  void modelToolsUseTheMcpTools() {
    ToolCallback getTicket =
        FunctionToolCallback.builder("getTicket", (Map<String, String> in) -> "t")
            .inputType(Map.class)
            .inputSchema("{\"type\":\"object\",\"properties\":{\"customer\":{},\"id\":{}}}")
            .build();
    ToolCallbackProvider mcp = () -> new ToolCallback[] {getTicket};
    assertThat(new ChatConfig().modelTools(mcp, new SimpleMeterRegistry()).all()).hasSize(1);
  }

  @Test
  void listCustomersIsHiddenFromTheModel() {
    HelpdeskProperties props =
        new HelpdeskProperties(
            null,
            null,
            null,
            new HelpdeskProperties.RequestLog(true, null, 0, 0),
            0,
            1,
            0,
            null,
            null,
            null);
    var filter = new ChatConfig().hideInternalTools(props);
    var tickets =
        McpConnectionInfo.builder()
            .clientInfo(new McpSchema.Implementation("client - tickets", "tickets", "1.0.0"))
            .build();
    assertThat(filter.test(tickets, McpSchema.Tool.builder().name("listCustomers").build()))
        .isFalse();
    assertThat(filter.test(tickets, McpSchema.Tool.builder().name("getTicket").build())).isTrue();
  }

  @Test
  void toolFailureIsRethrownWhenConfigured() {
    var processor = new ChatConfig().toolExecutionExceptionProcessor(true);
    assertThatThrownBy(() -> processor.process(failure()))
        .isInstanceOf(ToolExecutionException.class);
  }

  private static McpSyncClient client(String connection, String... tools) {
    McpSyncClient client = mock(McpSyncClient.class);
    when(client.getClientInfo())
        .thenReturn(new McpSchema.Implementation("client - " + connection, connection, "1.0.0"));
    when(client.listTools())
        .thenReturn(
            new McpSchema.ListToolsResult(
                Arrays.stream(tools)
                    .map(
                        name ->
                            McpSchema.Tool.builder()
                                .name(name)
                                .description(name)
                                .inputSchema(
                                    new McpSchema.JsonSchema(
                                        "object", Map.of(), null, null, null, null))
                                .build())
                    .toList(),
                null));
    return client;
  }

  @Test
  void theModelsToolsComeOnlyFromTheTicketServer() {
    HelpdeskProperties props =
        new HelpdeskProperties(
            null,
            null,
            null,
            new HelpdeskProperties.RequestLog(true, "requestlog", 0, 0),
            0,
            1,
            0,
            null,
            null,
            null);
    McpSyncClient tickets = client("tickets", "getTicket", "listCustomers");
    McpSyncClient requestLog = client("requestlog", "logRequest");
    ObjectProvider<List<McpSyncClient>> clients = mock();
    when(clients.getIfAvailable(any())).thenReturn(List.of(tickets, requestLog));

    ChatConfig config = new ChatConfig();
    var provider = config.mcpToolCallbacks(clients, config.hideInternalTools(props), props);

    assertThat(provider.getToolCallbacks())
        .extracting(t -> t.getToolDefinition().name())
        .containsExactly("getTicket");
    // the request-log server is never asked for its tools, so it may be down
    verify(requestLog, never()).listTools();
  }

  @Test
  void withoutMcpClientsTheModelGetsNoTools() {
    ObjectProvider<List<McpSyncClient>> clients = mock();
    when(clients.getIfAvailable(any())).thenReturn(List.of());
    HelpdeskProperties props =
        new HelpdeskProperties(
            null,
            null,
            null,
            new HelpdeskProperties.RequestLog(true, "requestlog", 0, 0),
            0,
            1,
            0,
            null,
            null,
            null);
    ChatConfig config = new ChatConfig();
    assertThat(
            config
                .mcpToolCallbacks(clients, config.hideInternalTools(props), props)
                .getToolCallbacks())
        .isEmpty();
  }
}
