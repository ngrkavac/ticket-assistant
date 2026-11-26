package com.example.assistant;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * Stores each /ask request in the request-log MCP server by calling its {@code logRequest} tool
 * directly (not through the model). Writes run in the background on a bounded executor, so a slow
 * or failing log server never delays or fails the user's request: when the queue is full the write
 * is dropped. Failures are only logged (without the question text) and counted in {@code
 * helpdesk_requestlog_writes_total{outcome="stored|failed|dropped"}}.
 */
@Component
class RequestLogClient implements DisposableBean {

  static final String TOOL = "logRequest";

  static final String LIST_TOOL = "listRecentRequests";

  static final String LIST_QUESTIONS_TOOL = "listQuestions";

  static final String ADD_QUESTION_TOOL = "addQuestion";

  /** The request-log server's limit for {@code userSubject} (K4). */
  static final int MAX_USER_SUBJECT_LENGTH = 255;

  static final String WRITES_METRIC = "helpdesk.requestlog.writes";

  /** Phrases in the request-log server's IllegalArgumentException messages (lower case). */
  private static final List<String> VALIDATION_MARKERS =
      List.of("invalid", "is required", "is longer than", "must be");

  private static final ObjectMapper JSON = new ObjectMapper();

  private static final TypeReference<List<LoggedRequest>> LOGGED_REQUESTS =
      new TypeReference<>() {};

  private static final TypeReference<List<CatalogQuestion>> QUESTIONS = new TypeReference<>() {};

  private static final TypeReference<CatalogQuestion> QUESTION = new TypeReference<>() {};

  private static final Logger log = LoggerFactory.getLogger(RequestLogClient.class);

  private final McpSyncClient client;

  private final ExecutorService executor;

  private final Counter stored;

  private final Counter failed;

  private final Counter dropped;

  @Autowired
  RequestLogClient(
      ObjectProvider<List<McpSyncClient>> clients, HelpdeskProperties props, MeterRegistry meters) {
    this(
        find(clients.getIfAvailable(List::of), props),
        boundedExecutor(props.requestLog().writeThreads(), props.requestLog().queueCapacity()),
        meters);
  }

  RequestLogClient(McpSyncClient client, ExecutorService executor, MeterRegistry meters) {
    this.client = client;
    this.executor = executor;
    this.stored = writes(meters, "stored");
    this.failed = writes(meters, "failed");
    this.dropped = writes(meters, "dropped");
  }

  private static Counter writes(MeterRegistry meters, String outcome) {
    return Counter.builder(WRITES_METRIC)
        .description("Background writes of /ask requests to the request-log server")
        .tag("outcome", outcome)
        .register(meters);
  }

  /**
   * At most {@code threads} virtual threads write concurrently and at most {@code queueCapacity}
   * writes wait; anything beyond that is rejected (AbortPolicy) and dropped by {@link #record}.
   */
  static ThreadPoolExecutor boundedExecutor(int threads, int queueCapacity) {
    ThreadPoolExecutor executor =
        new ThreadPoolExecutor(
            threads,
            threads,
            30,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(queueCapacity),
            Thread.ofVirtual().name("request-log-", 0).factory(),
            new ThreadPoolExecutor.AbortPolicy());
    executor.allowCoreThreadTimeOut(true);
    return executor;
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
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "helpdesk.request-log.enabled=true but no MCP client connection '"
                        + connection
                        + "' is configured (spring.ai.mcp.client.sse.connections."
                        + connection
                        + ".url)"));
  }

  /**
   * Spring AI sets the client title to the connection name from spring.ai.mcp.client.*.connections.
   */
  static boolean isConnection(McpSchema.Implementation clientInfo, String connection) {
    return clientInfo != null && connection.equals(clientInfo.title());
  }

  boolean enabled() {
    return client != null;
  }

  /**
   * Stores one request in the background. {@code userSubject} (the caller's JWT {@code sub}) and
   * {@code toolCalls} (a K4 JSON array from {@link ToolCallAudit#toJson()}) are optional and sent
   * only when present. The subject is shortened to {@value #MAX_USER_SUBJECT_LENGTH} characters,
   * the server's limit, so an unusually long one cannot make the server reject the whole entry.
   */
  void record(
      String requestId,
      String question,
      String customer,
      String answer,
      String errorType,
      long durationMs,
      @Nullable String userSubject,
      @Nullable String toolCalls) {
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
    args.put("customer", customer);
    String subject = userSubject(userSubject);
    if (subject != null) {
      args.put("userSubject", subject);
    }
    if (toolCalls != null && !toolCalls.isBlank()) {
      args.put("toolCalls", toolCalls);
    }
    try {
      executor.execute(() -> send(requestId, args));
    } catch (RejectedExecutionException e) { // queue full or shutting down
      dropped.increment();
      log.warn("Request {} not stored: request-log write queue is full or closed", requestId);
    }
  }

  /** Null when blank; otherwise trimmed and at most {@value #MAX_USER_SUBJECT_LENGTH} chars. */
  @Nullable
  static String userSubject(@Nullable String subject) {
    if (subject == null || subject.isBlank()) {
      return null;
    }
    String s = subject.trim();
    return s.length() > MAX_USER_SUBJECT_LENGTH ? s.substring(0, MAX_USER_SUBJECT_LENGTH) : s;
  }

  private void send(String requestId, Map<String, Object> args) {
    try {
      McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest(TOOL, args));
      if (Boolean.TRUE.equals(result.isError())) {
        failed.increment();
        log.warn("Request {} not stored: request-log server returned an error", requestId);
      } else {
        stored.increment();
      }
    } catch (RuntimeException e) {
      failed.increment();
      log.warn("Request {} not stored: {}", requestId, e.getClass().getSimpleName());
    }
  }

  /**
   * A stored request as shown in the UI history. Answers and error details are deliberately left
   * out.
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record LoggedRequest(
      String requestId, String question, String status, String customer, String createdAt) {}

  /** A question offered in the UI. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record CatalogQuestion(long id, String text) {}

  /**
   * Reads the newest stored requests through the {@code listRecentRequests} tool (synchronously),
   * only the given customer's when customer is not null.
   *
   * @throws RequestLogUnavailableException if logging is disabled or the server fails
   */
  List<LoggedRequest> recent(int limit, String customer) {
    Map<String, Object> args = new HashMap<>();
    args.put("limit", limit);
    if (customer != null) {
      args.put("customer", customer);
    }
    return call(LIST_TOOL, args, LOGGED_REQUESTS);
  }

  /**
   * @throws RequestLogUnavailableException if logging is disabled or the server fails
   */
  List<CatalogQuestion> questions() {
    return call(LIST_QUESTIONS_TOOL, Map.of(), QUESTIONS);
  }

  /**
   * Adds a question to the UI list; returns the existing one if the text is already there.
   *
   * @throws RequestLogRejectedException if the server rejects the text as invalid
   * @throws RequestLogUnavailableException if logging is disabled or the server fails
   */
  CatalogQuestion addQuestion(String text) {
    return call(ADD_QUESTION_TOOL, Map.of("text", text), QUESTION);
  }

  /** Calls a tool synchronously and parses its JSON text result. */
  private <T> T call(String tool, Map<String, Object> args, TypeReference<T> type) {
    if (client == null) {
      throw new RequestLogUnavailableException("Request logging is disabled", null);
    }
    McpSchema.CallToolResult result;
    try {
      result = client.callTool(new McpSchema.CallToolRequest(tool, args));
    } catch (RuntimeException e) {
      throw new RequestLogUnavailableException(tool + " failed", e);
    }
    if (result.content() == null) {
      throw new RequestLogUnavailableException(tool + " returned an error", null);
    }
    String text =
        result.content().stream()
            .filter(McpSchema.TextContent.class::isInstance)
            .map(c -> ((McpSchema.TextContent) c).text())
            .findFirst()
            .orElse(null);
    if (Boolean.TRUE.equals(result.isError())) {
      if (isValidationError(text)) {
        throw new RequestLogRejectedException(tool + " rejected the request: " + text);
      }
      throw new RequestLogUnavailableException(tool + " returned an error", null);
    }
    if (text == null) {
      throw new RequestLogUnavailableException(tool + " returned no text", null);
    }
    try {
      return JSON.readValue(text, type);
    } catch (Exception e) {
      throw new RequestLogUnavailableException(tool + " returned unexpected content", e);
    }
  }

  /**
   * The request-log server reports invalid arguments as an error result whose text is the
   * IllegalArgumentException message, e.g. "Invalid customer", "text is required" or "text is
   * longer than 1000 characters". Anything else (server or database failure) is not a validation
   * error.
   */
  static boolean isValidationError(String errorText) {
    if (errorText == null) {
      return false;
    }
    String t = errorText.toLowerCase(Locale.ROOT);
    return VALIDATION_MARKERS.stream().anyMatch(t::contains);
  }

  /** Lets pending writes finish on shutdown. */
  @Override
  public void destroy() {
    executor.close();
  }
}
