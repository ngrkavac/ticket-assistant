package com.example.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

class RequestLogClientTests {

  private static HelpdeskProperties props(boolean enabled) {
    return new HelpdeskProperties(
        null, null, null, new HelpdeskProperties.RequestLog(enabled, null), 0, 0, 0);
  }

  private static McpSyncClient client(String connection) {
    McpSyncClient c = mock(McpSyncClient.class);
    when(c.getClientInfo())
        .thenReturn(
            new McpSchema.Implementation(
                "spring-ai-mcp-client - " + connection, connection, "1.0.0"));
    return c;
  }

  @SuppressWarnings("unchecked")
  private static ObjectProvider<List<McpSyncClient>> provider(List<McpSyncClient> clients) {
    ObjectProvider<List<McpSyncClient>> p = mock(ObjectProvider.class);
    when(p.getIfAvailable(any())).thenReturn(clients);
    return p;
  }

  @Test
  void picksTheConfiguredConnectionAndCallsLogRequest() {
    McpSyncClient tickets = client("tickets");
    McpSyncClient requestLog = client("requestlog");
    when(requestLog.callTool(any()))
        .thenReturn(McpSchema.CallToolResult.builder().isError(false).build());
    var log = new RequestLogClient(provider(List.of(tickets, requestLog)), props(true));
    assertThat(log.enabled()).isTrue();

    log.record("req-1", "How?", null, "Like this.", null, 15);
    log.destroy(); // waits for the background write

    ArgumentCaptor<McpSchema.CallToolRequest> call =
        ArgumentCaptor.forClass(McpSchema.CallToolRequest.class);
    verify(requestLog).callTool(call.capture());
    verify(tickets, never()).callTool(any());
    assertThat(call.getValue().name()).isEqualTo("logRequest");
    assertThat(call.getValue().arguments())
        .containsEntry("requestId", "req-1")
        .containsEntry("question", "How?")
        .containsEntry("answer", "Like this.")
        .containsEntry("status", "SUCCESS")
        .containsEntry("durationMs", 15L);
  }

  @Test
  void failedRequestIsSentWithErrorStatus() {
    McpSyncClient c = client("requestlog");
    when(c.callTool(any())).thenReturn(McpSchema.CallToolResult.builder().isError(true).build());
    var log = new RequestLogClient(c, Executors.newVirtualThreadPerTaskExecutor());
    log.record("req-2", "q", null, null, "RestClientException", 3);
    log.destroy();

    ArgumentCaptor<McpSchema.CallToolRequest> call =
        ArgumentCaptor.forClass(McpSchema.CallToolRequest.class);
    verify(c).callTool(call.capture());
    assertThat(call.getValue().arguments())
        .containsEntry("status", "ERROR")
        .containsEntry("errorType", "RestClientException");
  }

  @Test
  void serverFailureDoesNotPropagate() {
    McpSyncClient c = client("requestlog");
    when(c.callTool(any())).thenThrow(new IllegalStateException("connection refused"));
    var log = new RequestLogClient(c, Executors.newVirtualThreadPerTaskExecutor());
    log.record("req-3", "q", null, "a", null, 1);
    log.destroy();
    verify(c).callTool(any());
  }

  @Test
  void writesAfterShutdownAreDroppedQuietly() {
    McpSyncClient c = client("requestlog");
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    var log = new RequestLogClient(c, executor);
    log.destroy();
    log.record("req-4", "q", null, "a", null, 1);
    verify(c, never()).callTool(any());
  }

  @Test
  void disabledLoggingDoesNothing() {
    var log = new RequestLogClient(provider(List.of()), props(false));
    assertThat(log.enabled()).isFalse();
    log.record("req-5", "q", null, "a", null, 1);
    log.destroy();
  }

  @Test
  void enabledWithoutConnectionFailsFast() {
    assertThatThrownBy(
            () -> new RequestLogClient(provider(List.of(client("tickets"))), props(true)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("connections.requestlog.url");
  }

  @Test
  void requestLogToolsAreHiddenFromTheModel() {
    var filter = new ChatConfig().hideRequestLogTools(props(true));
    McpSchema.Tool tool = McpSchema.Tool.builder().name("logRequest").build();
    assertThat(filter.test(info("requestlog"), tool)).isFalse();
    assertThat(filter.test(info("tickets"), tool)).isTrue();
    assertThat(RequestLogClient.isConnection(null, "requestlog")).isFalse();
  }

  @Test
  void recentParsesTheToolResult() {
    McpSyncClient c = client("requestlog");
    String json =
        """
        [{"id":2,"requestId":"r-2","question":"VPN?","answer":"Restart it.","status":"SUCCESS",
          "errorType":null,"durationMs":12,"customer":"acme","createdAt":"2026-11-15T10:42:00Z"}]
        """;
    when(c.callTool(any()))
        .thenReturn(
            McpSchema.CallToolResult.builder()
                .isError(false)
                .addContent(new McpSchema.TextContent(json))
                .build());
    var log = new RequestLogClient(c, Executors.newVirtualThreadPerTaskExecutor());

    assertThat(log.recent(5, null))
        .containsExactly(
            new RequestLogClient.LoggedRequest(
                "r-2", "VPN?", "SUCCESS", "acme", "2026-11-15T10:42:00Z"));
    ArgumentCaptor<McpSchema.CallToolRequest> call =
        ArgumentCaptor.forClass(McpSchema.CallToolRequest.class);
    verify(c).callTool(call.capture());
    assertThat(call.getValue().name()).isEqualTo("listRecentRequests");
    assertThat(call.getValue().arguments()).containsEntry("limit", 5).doesNotContainKey("customer");
  }

  @Test
  void recentPassesTheCustomerFilter() {
    McpSyncClient c = client("requestlog");
    when(c.callTool(any())).thenReturn(text("[]"));
    var log = new RequestLogClient(c, Executors.newVirtualThreadPerTaskExecutor());

    assertThat(log.recent(5, "acme")).isEmpty();
    ArgumentCaptor<McpSchema.CallToolRequest> call =
        ArgumentCaptor.forClass(McpSchema.CallToolRequest.class);
    verify(c).callTool(call.capture());
    assertThat(call.getValue().arguments())
        .containsEntry("limit", 5)
        .containsEntry("customer", "acme");
  }

  @Test
  void recordsTheCustomer() {
    McpSyncClient c = client("requestlog");
    when(c.callTool(any())).thenReturn(McpSchema.CallToolResult.builder().isError(false).build());
    var log = new RequestLogClient(c, Executors.newVirtualThreadPerTaskExecutor());
    log.record("req-6", "q", "globex", "a", null, 1);
    log.destroy();

    ArgumentCaptor<McpSchema.CallToolRequest> call =
        ArgumentCaptor.forClass(McpSchema.CallToolRequest.class);
    verify(c).callTool(call.capture());
    assertThat(call.getValue().arguments()).containsEntry("customer", "globex");
  }

  @Test
  void listsAndAddsQuestions() {
    McpSyncClient c = client("requestlog");
    when(c.callTool(any()))
        .thenReturn(
            text(
                """
                [{"id":1,"text":"How do I reset my password?","createdAt":"2026-11-15T10:42:00Z"}]
                """))
        .thenReturn(
            text("{\"id\":7,\"text\":\"Printer?\",\"createdAt\":\"2026-11-17T09:00:00Z\"}"));
    var log = new RequestLogClient(c, Executors.newVirtualThreadPerTaskExecutor());

    assertThat(log.questions())
        .containsExactly(new RequestLogClient.CatalogQuestion(1, "How do I reset my password?"));
    assertThat(log.addQuestion("Printer?"))
        .isEqualTo(new RequestLogClient.CatalogQuestion(7, "Printer?"));

    ArgumentCaptor<McpSchema.CallToolRequest> call =
        ArgumentCaptor.forClass(McpSchema.CallToolRequest.class);
    verify(c, org.mockito.Mockito.times(2)).callTool(call.capture());
    assertThat(call.getAllValues().get(0).name()).isEqualTo("listQuestions");
    assertThat(call.getAllValues().get(1).name()).isEqualTo("addQuestion");
    assertThat(call.getAllValues().get(1).arguments()).containsEntry("text", "Printer?");
  }

  @Test
  void questionsFailWhenServerErrors() {
    McpSyncClient c = client("requestlog");
    when(c.callTool(any())).thenReturn(McpSchema.CallToolResult.builder().isError(true).build());
    var log = new RequestLogClient(c, Executors.newVirtualThreadPerTaskExecutor());
    assertThatThrownBy(log::questions).isInstanceOf(RequestLogUnavailableException.class);
    assertThatThrownBy(() -> log.addQuestion("q"))
        .isInstanceOf(RequestLogUnavailableException.class);
  }

  private static McpSchema.CallToolResult text(String json) {
    return McpSchema.CallToolResult.builder()
        .isError(false)
        .addContent(new McpSchema.TextContent(json))
        .build();
  }

  @Test
  void recentFailsWhenServerErrorsOrLoggingIsDisabled() {
    McpSyncClient c = client("requestlog");
    when(c.callTool(any())).thenReturn(McpSchema.CallToolResult.builder().isError(true).build());
    var log = new RequestLogClient(c, Executors.newVirtualThreadPerTaskExecutor());
    assertThatThrownBy(() -> log.recent(5, null))
        .isInstanceOf(RequestLogUnavailableException.class);

    when(c.callTool(any())).thenThrow(new IllegalStateException("connection refused"));
    assertThatThrownBy(() -> log.recent(5, null))
        .isInstanceOf(RequestLogUnavailableException.class);

    var disabled = new RequestLogClient(provider(List.of()), props(false));
    assertThatThrownBy(() -> disabled.recent(5, null))
        .isInstanceOf(RequestLogUnavailableException.class);
  }

  @Test
  void validationErrorsAreRejectionsNotOutages() {
    McpSyncClient c = client("requestlog");
    when(c.callTool(any()))
        .thenReturn(error("text is longer than 1000 characters"))
        .thenReturn(error("Invalid customer"))
        .thenReturn(error("could not open JDBC connection"));
    var log = new RequestLogClient(c, Executors.newVirtualThreadPerTaskExecutor());

    assertThatThrownBy(() -> log.addQuestion("x"))
        .isInstanceOf(RequestLogRejectedException.class)
        .hasMessageContaining("longer than 1000");
    assertThatThrownBy(() -> log.recent(5, "bad customer"))
        .isInstanceOf(RequestLogRejectedException.class);
    assertThatThrownBy(log::questions).isInstanceOf(RequestLogUnavailableException.class);
  }

  @Test
  void recognisesValidationMessages() {
    assertThat(RequestLogClient.isValidationError("text is required")).isTrue();
    assertThat(RequestLogClient.isValidationError("Invalid requestId")).isTrue();
    assertThat(RequestLogClient.isValidationError("limit must be between 1 and 100")).isTrue();
    assertThat(RequestLogClient.isValidationError("Connection refused")).isFalse();
    assertThat(RequestLogClient.isValidationError(null)).isFalse();
  }

  private static McpSchema.CallToolResult error(String message) {
    return McpSchema.CallToolResult.builder()
        .isError(true)
        .addContent(new McpSchema.TextContent(message))
        .build();
  }

  private static org.springframework.ai.mcp.McpConnectionInfo info(String connection) {
    return org.springframework.ai.mcp.McpConnectionInfo.builder()
        .clientInfo(new McpSchema.Implementation("client - " + connection, connection, "1.0.0"))
        .build();
  }
}
