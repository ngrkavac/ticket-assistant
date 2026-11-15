package com.example.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

class RequestLogClientTests {

	private static HelpdeskProperties props(boolean enabled) {
		return new HelpdeskProperties(null, null, null, new HelpdeskProperties.RequestLog(enabled, null), 0, 0);
	}

	private static McpSyncClient client(String connection) {
		McpSyncClient c = mock(McpSyncClient.class);
		when(c.getClientInfo()).thenReturn(new McpSchema.Implementation("spring-ai-mcp-client - " + connection,
				connection, "1.0.0"));
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
		when(requestLog.callTool(any())).thenReturn(McpSchema.CallToolResult.builder().isError(false).build());
		var log = new RequestLogClient(provider(List.of(tickets, requestLog)), props(true));
		assertThat(log.enabled()).isTrue();

		log.record("req-1", "How?", "Like this.", null, 15);
		log.destroy(); // waits for the background write

		ArgumentCaptor<McpSchema.CallToolRequest> call = ArgumentCaptor.forClass(McpSchema.CallToolRequest.class);
		verify(requestLog).callTool(call.capture());
		verify(tickets, never()).callTool(any());
		assertThat(call.getValue().name()).isEqualTo("logRequest");
		assertThat(call.getValue().arguments()).containsEntry("requestId", "req-1")
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
		log.record("req-2", "q", null, "RestClientException", 3);
		log.destroy();

		ArgumentCaptor<McpSchema.CallToolRequest> call = ArgumentCaptor.forClass(McpSchema.CallToolRequest.class);
		verify(c).callTool(call.capture());
		assertThat(call.getValue().arguments()).containsEntry("status", "ERROR")
			.containsEntry("errorType", "RestClientException");
	}

	@Test
	void serverFailureDoesNotPropagate() {
		McpSyncClient c = client("requestlog");
		when(c.callTool(any())).thenThrow(new IllegalStateException("connection refused"));
		var log = new RequestLogClient(c, Executors.newVirtualThreadPerTaskExecutor());
		log.record("req-3", "q", "a", null, 1);
		log.destroy();
		verify(c).callTool(any());
	}

	@Test
	void writesAfterShutdownAreDroppedQuietly() {
		McpSyncClient c = client("requestlog");
		ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
		var log = new RequestLogClient(c, executor);
		log.destroy();
		log.record("req-4", "q", "a", null, 1);
		verify(c, never()).callTool(any());
	}

	@Test
	void disabledLoggingDoesNothing() {
		var log = new RequestLogClient(provider(List.of()), props(false));
		assertThat(log.enabled()).isFalse();
		log.record("req-5", "q", "a", null, 1);
		log.destroy();
	}

	@Test
	void enabledWithoutConnectionFailsFast() {
		assertThatThrownBy(() -> new RequestLogClient(provider(List.of(client("tickets"))), props(true)))
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
		String json = """
				[{"id":2,"requestId":"r-2","question":"VPN?","answer":"Restart it.","status":"SUCCESS",
				  "errorType":null,"durationMs":12,"createdAt":"2026-11-15T10:42:00Z"}]
				""";
		when(c.callTool(any())).thenReturn(McpSchema.CallToolResult.builder()
			.isError(false)
			.addContent(new McpSchema.TextContent(json))
			.build());
		var log = new RequestLogClient(c, Executors.newVirtualThreadPerTaskExecutor());

		assertThat(log.recent(5)).containsExactly(
				new RequestLogClient.LoggedRequest("r-2", "VPN?", "SUCCESS", "2026-11-15T10:42:00Z"));
		ArgumentCaptor<McpSchema.CallToolRequest> call = ArgumentCaptor.forClass(McpSchema.CallToolRequest.class);
		verify(c).callTool(call.capture());
		assertThat(call.getValue().name()).isEqualTo("listRecentRequests");
		assertThat(call.getValue().arguments()).containsEntry("limit", 5);
	}

	@Test
	void recentFailsWhenServerErrorsOrLoggingIsDisabled() {
		McpSyncClient c = client("requestlog");
		when(c.callTool(any())).thenReturn(McpSchema.CallToolResult.builder().isError(true).build());
		var log = new RequestLogClient(c, Executors.newVirtualThreadPerTaskExecutor());
		assertThatThrownBy(() -> log.recent(5)).isInstanceOf(RequestLogUnavailableException.class);

		when(c.callTool(any())).thenThrow(new IllegalStateException("connection refused"));
		assertThatThrownBy(() -> log.recent(5)).isInstanceOf(RequestLogUnavailableException.class);

		var disabled = new RequestLogClient(provider(List.of()), props(false));
		assertThatThrownBy(() -> disabled.recent(5)).isInstanceOf(RequestLogUnavailableException.class);
	}

	private static org.springframework.ai.mcp.McpConnectionInfo info(String connection) {
		return org.springframework.ai.mcp.McpConnectionInfo.builder()
			.clientInfo(new McpSchema.Implementation("client - " + connection, connection, "1.0.0"))
			.build();
	}

}
