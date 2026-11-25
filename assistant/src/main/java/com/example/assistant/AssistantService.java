package com.example.assistant;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

/**
 * Calls the LLM, with a cap on concurrent in-flight requests so slow model calls cannot pile up. A
 * request waits up to helpdesk.queue-wait for a permit before it is rejected with 429. Every
 * request that reaches the model is stored via {@link RequestLogClient}.
 */
@Service
class AssistantService {

  private static final Logger log = LoggerFactory.getLogger(AssistantService.class);

  private final ChatClient chat;

  private final ModelTools tools;

  private final Semaphore permits;

  private final RequestLogClient requestLog;

  private final Duration queueWait;

  private final Duration retryAfter;

  AssistantService(
      ChatClient chat, ModelTools tools, HelpdeskProperties props, RequestLogClient requestLog) {
    this.chat = chat;
    this.tools = tools;
    this.requestLog = requestLog;
    this.permits = new Semaphore(props.maxConcurrentRequests());
    this.queueWait = props.queueWait();
    this.retryAfter = props.retryAfter();
  }

  /**
   * Asks the model on behalf of {@code principal}, for {@code customer} (already checked against
   * the principal by the caller). The user text is the question as typed. The customer goes into
   * the system prompt and, with the principal, into the tool context, where {@link
   * CustomerScopedToolCallback} limits every ticket tool call to it. The model is offered only the
   * tools the principal may use ({@link ModelTools}), and every tool call is collected in a {@link
   * ToolCallAudit}.
   */
  public String ask(String question, String customer, HelpdeskPrincipal principal) {
    Objects.requireNonNull(customer, "customer");
    Objects.requireNonNull(principal, "principal");
    try {
      if (!permits.tryAcquire(queueWait.toNanos(), TimeUnit.NANOSECONDS)) {
        throw new TooManyRequestsException(retryAfter);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new TooManyRequestsException(retryAfter);
    }
    String requestId = currentRequestId();
    long start = System.nanoTime();
    String answer = null;
    String errorType = null;
    ToolCallAudit audit = new ToolCallAudit();
    try {
      answer =
          chat.prompt()
              .system(s -> s.param(ChatConfig.CUSTOMER_PARAM, customer))
              .toolCallbacks(tools.offeredTo(principal))
              .toolContext(
                  Map.of(
                      CustomerScopedToolCallback.CUSTOMER, customer,
                      CustomerScopedToolCallback.PRINCIPAL, principal,
                      CustomerScopedToolCallback.AUDIT, audit))
              .user(question)
              .call()
              .content();
      return answer;
    } catch (RuntimeException | Error e) {
      errorType = e.getClass().getSimpleName();
      throw e;
    } finally {
      permits.release();
      logWrites(requestId, principal, audit);
      requestLog.record(
          requestId,
          question,
          customer,
          answer,
          errorType,
          (System.nanoTime() - start) / 1_000_000);
    }
  }

  /**
   * Every call of a write tool (e.g. {@code createTicket}) is logged with the user's subject, so
   * created tickets can be traced to a user (T1.5). {@link ToolCallAudit#toJson()} is the K4 {@code
   * toolCalls} value for the request log.
   */
  private static void logWrites(
      String requestId, HelpdeskPrincipal principal, ToolCallAudit audit) {
    for (ToolCallAudit.ToolCall call : audit.calls()) {
      if (CustomerScopedToolCallback.WRITE_TOOLS.contains(call.tool())) {
        log.info(
            "Request {}: tool {} by {} for customer {}: {} {}",
            requestId,
            call.tool(),
            principal.subject(),
            call.customer(),
            call.outcome(),
            call.resultId() == null ? "" : call.resultId());
      }
    }
    if (audit.dropped() > 0) {
      log.warn("Request {}: {} tool calls not audited (limit reached)", requestId, audit.dropped());
    }
  }

  /** Same id as the X-Request-Id header (set by {@link RequestIdFilter}). */
  private static String currentRequestId() {
    String id = MDC.get("requestId");
    return id != null ? id : UUID.randomUUID().toString();
  }
}
