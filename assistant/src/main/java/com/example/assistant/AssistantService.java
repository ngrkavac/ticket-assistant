package com.example.assistant;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
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

  private final ChatClient chat;

  private final Semaphore permits;

  private final RequestLogClient requestLog;

  private final Duration queueWait;

  private final Duration retryAfter;

  AssistantService(ChatClient chat, HelpdeskProperties props, RequestLogClient requestLog) {
    this.chat = chat;
    this.requestLog = requestLog;
    this.permits = new Semaphore(props.maxConcurrentRequests());
    this.queueWait = props.queueWait();
    this.retryAfter = props.retryAfter();
  }

  /**
   * Asks the model on behalf of {@code principal}, for {@code customer} (already checked against
   * the principal by the caller). The user text is the question as typed. The customer goes into
   * the system prompt and, with the principal, into the tool context, where {@link
   * CustomerScopedToolCallback} limits every ticket tool call to it.
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
    try {
      answer =
          chat.prompt()
              .system(s -> s.param(ChatConfig.CUSTOMER_PARAM, customer))
              .toolContext(
                  Map.of(
                      CustomerScopedToolCallback.CUSTOMER, customer,
                      CustomerScopedToolCallback.PRINCIPAL, principal))
              .user(question)
              .call()
              .content();
      return answer;
    } catch (RuntimeException | Error e) {
      errorType = e.getClass().getSimpleName();
      throw e;
    } finally {
      permits.release();
      requestLog.record(
          requestId,
          question,
          customer,
          answer,
          errorType,
          (System.nanoTime() - start) / 1_000_000);
    }
  }

  /** Same id as the X-Request-Id header (set by {@link RequestIdFilter}). */
  private static String currentRequestId() {
    String id = MDC.get("requestId");
    return id != null ? id : UUID.randomUUID().toString();
  }
}
