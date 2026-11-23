package com.example.assistant;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "helpdesk")
public record HelpdeskProperties(
    Rag rag,
    Security security,
    Kb kb,
    @Valid RequestLog requestLog,
    @Min(1) @Max(10000) int maxQuestionLength,
    @Min(0) @Max(1000) int maxConcurrentRequests,
    @Min(0) @Max(MAX_CATALOG_QUESTION_LENGTH) int maxCatalogQuestionLength,
    Duration queueWait,
    Duration retryAfter) {

  /** The longest catalog question the request-log server stores. */
  static final int MAX_CATALOG_QUESTION_LENGTH = 1000;

  public HelpdeskProperties {
    rag = rag == null ? new Rag(3, 0.3) : rag;
    security = security == null ? new Security(null) : security;
    kb =
        kb == null || kb.location() == null || kb.location().isBlank()
            ? new Kb(Kb.DEFAULT_LOCATION)
            : kb;
    requestLog = requestLog == null ? new RequestLog(true, null, 0, 0) : requestLog;
    maxQuestionLength = maxQuestionLength == 0 ? 1000 : maxQuestionLength;
    maxConcurrentRequests = maxConcurrentRequests == 0 ? 8 : maxConcurrentRequests;
    maxCatalogQuestionLength =
        maxCatalogQuestionLength == 0 ? MAX_CATALOG_QUESTION_LENGTH : maxCatalogQuestionLength;
    queueWait = queueWait == null ? Duration.ofSeconds(1) : queueWait;
    if (queueWait.isNegative()) {
      throw new IllegalArgumentException("helpdesk.queue-wait must not be negative");
    }
    retryAfter = retryAfter == null ? Duration.ofSeconds(5) : retryAfter;
    if (retryAfter.isNegative() || retryAfter.isZero()) {
      throw new IllegalArgumentException("helpdesk.retry-after must be positive");
    }
  }

  /** The Retry-After header value: whole seconds, rounded up. */
  long retryAfterSeconds() {
    return retryAfterSeconds(retryAfter);
  }

  static long retryAfterSeconds(Duration d) {
    return d.plusNanos(999_999_999).getSeconds();
  }

  public record Rag(@Min(1) @Max(20) int topK, @Min(0) @Max(1) double similarityThreshold) {}

  /**
   * Where knowledge base markdown files are read from: classpath:..., file:... (Ant-style
   * patterns).
   */
  public record Kb(String location) {

    static final String DEFAULT_LOCATION = "classpath:kb/*.md";
  }

  /**
   * Every /ask request is stored through the MCP server on the named client connection
   * (spring.ai.mcp.client.sse.connections.&lt;connection&gt;). Its tools are never offered to the
   * model. Writes run in the background on at most {@code writeThreads} threads; at most {@code
   * queueCapacity} writes wait for a thread, and further writes are dropped (counted in {@code
   * helpdesk_requestlog_writes_total{outcome="dropped"}}) so a slow log server never holds up /ask.
   */
  public record RequestLog(
      boolean enabled,
      String connection,
      @Min(0) @Max(100_000) int queueCapacity,
      @Min(0) @Max(256) int writeThreads) {

    static final String DEFAULT_CONNECTION = "requestlog";

    static final int DEFAULT_QUEUE_CAPACITY = 1000;

    static final int DEFAULT_WRITE_THREADS = 4;

    public RequestLog {
      connection =
          connection == null || connection.isBlank() ? DEFAULT_CONNECTION : connection.trim();
      queueCapacity = queueCapacity == 0 ? DEFAULT_QUEUE_CAPACITY : queueCapacity;
      writeThreads = writeThreads == 0 ? DEFAULT_WRITE_THREADS : writeThreads;
    }
  }

  /** If apiKey is blank, /ask is open (local dev only). */
  public record Security(String apiKey) {}
}
