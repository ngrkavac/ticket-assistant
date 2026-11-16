package com.example.assistant;

import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.slf4j.MDC;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

/**
 * Calls the LLM, with a cap on concurrent in-flight requests so slow model calls cannot pile up.
 * Every request that reaches the model is stored via {@link RequestLogClient}.
 */
@Service
class AssistantService {

  private static final long QUEUE_WAIT_MS = 1000;

  private final ChatClient chat;

  private final Semaphore permits;

  private final RequestLogClient requestLog;

  AssistantService(ChatClient chat, HelpdeskProperties props, RequestLogClient requestLog) {
    this.chat = chat;
    this.requestLog = requestLog;
    this.permits = new Semaphore(props.maxConcurrentRequests());
  }

  public String ask(String question) {
    try {
      if (!permits.tryAcquire(QUEUE_WAIT_MS, TimeUnit.MILLISECONDS)) {
        throw new TooManyRequestsException();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new TooManyRequestsException();
    }
    String requestId = currentRequestId();
    long start = System.nanoTime();
    String answer = null;
    String errorType = null;
    try {
      answer = chat.prompt().user(question).call().content();
      return answer;
    } catch (RuntimeException | Error e) {
      errorType = e.getClass().getSimpleName();
      throw e;
    } finally {
      permits.release();
      requestLog.record(
          requestId, question, answer, errorType, (System.nanoTime() - start) / 1_000_000);
    }
  }

  /** Same id as the X-Request-Id header (set by {@link RequestIdFilter}). */
  private static String currentRequestId() {
    String id = MDC.get("requestId");
    return id != null ? id : UUID.randomUUID().toString();
  }
}
