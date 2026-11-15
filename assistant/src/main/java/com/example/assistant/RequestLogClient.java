package com.example.assistant;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Stores each /ask request in the request-log MCP server by calling its {@code logRequest} tool
 * directly (not through the model). Writes run in the background, so a slow or failing log server
 * never delays or fails the user's request; failures are only logged (without the question text).
 */
@Component
class RequestLogClient implements DisposableBean {

	static final String TOOL = "logRequest";

	private static final Logger log = LoggerFactory.getLogger(RequestLogClient.class);

	private final McpSyncClient client;

	private final ExecutorService executor;

	@Autowired
	RequestLogClient(ObjectProvider<List<McpSyncClient>> clients, HelpdeskProperties props) {
		this(find(clients.getIfAvailable(List::of), props), Executors.newVirtualThreadPerTaskExecutor());
	}

	RequestLogClient(McpSyncClient client, ExecutorService executor) {
		this.client = client;
		this.executor = executor;
	}

	private static McpSyncClient find(List<McpSyncClient> clients, HelpdeskProperties props) {
		if (!props.requestLog().enabled()) {
			log.info("Request logging is disabled");
			return null;
		}
		String connection = props.requestLog().connection();
		return clients.stream()
			.filter(c -> isConnection(c.getClientInfo(), connection))
			.findFirst()
			.orElseThrow(() -> new IllegalStateException("helpdesk.request-log.enabled=true but no MCP client connection '"
					+ connection + "' is configured (spring.ai.mcp.client.sse.connections." + connection + ".url)"));
	}

	/** Spring AI sets the client title to the connection name from spring.ai.mcp.client.*.connections. */
	static boolean isConnection(McpSchema.Implementation clientInfo, String connection) {
		return clientInfo != null && connection.equals(clientInfo.title());
	}

	boolean enabled() {
		return client != null;
	}

	void record(String requestId, String question, String answer, String errorType, long durationMs) {
		if (client == null) {
			return;
		}
		Map<String, Object> args = new HashMap<>();
		args.put("requestId", requestId);
		args.put("question", question);
		args.put("answer", answer);
		args.put("status", errorType == null ? "SUCCESS" : "ERROR");
		args.put("errorType", errorType);
		args.put("durationMs", durationMs);
		try {
			executor.execute(() -> send(requestId, args));
		}
		catch (RuntimeException e) { // rejected during shutdown
			log.warn("Request {} not stored: {}", requestId, e.toString());
		}
	}

	private void send(String requestId, Map<String, Object> args) {
		try {
			McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest(TOOL, args));
			if (Boolean.TRUE.equals(result.isError())) {
				log.warn("Request {} not stored: request-log server returned an error", requestId);
			}
		}
		catch (RuntimeException e) {
			log.warn("Request {} not stored: {}", requestId, e.getClass().getSimpleName());
		}
	}

	/** Lets pending writes finish on shutdown. */
	@Override
	public void destroy() {
		executor.close();
	}

}
