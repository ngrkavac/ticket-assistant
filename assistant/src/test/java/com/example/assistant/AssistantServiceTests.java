package com.example.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.ai.chat.client.ChatClient;

class AssistantServiceTests {

  private final RequestLogClient log = mock(RequestLogClient.class);

  private static HelpdeskProperties props(int maxConcurrent) {
    return new HelpdeskProperties(null, null, null, null, 0, maxConcurrent, 0, null, null, null);
  }

  @Test
  void returnsModelContent() {
    ChatClient chat = mock(ChatClient.class, RETURNS_DEEP_STUBS);
    when(chat.prompt().user("hi").call().content()).thenReturn("hello");
    assertThat(new AssistantService(chat, props(2), log).ask("hi", null)).isEqualTo("hello");
  }

  @Test
  void releasesPermitWhenModelFails() {
    ChatClient chat = mock(ChatClient.class, RETURNS_DEEP_STUBS);
    when(chat.prompt().user("boom").call().content()).thenThrow(new IllegalStateException("down"));
    when(chat.prompt().user("ok").call().content()).thenReturn("fine");
    AssistantService service = new AssistantService(chat, props(1), log);
    assertThatThrownBy(() -> service.ask("boom", null)).isInstanceOf(IllegalStateException.class);
    // the single permit must be free again
    assertThat(service.ask("ok", null)).isEqualTo("fine");
  }

  @Test
  void rejectsWhenAllPermitsAreBusy() throws Exception {
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch finish = new CountDownLatch(1);
    ChatClient chat = mock(ChatClient.class, RETURNS_DEEP_STUBS);
    when(chat.prompt().user("slow").call().content())
        .thenAnswer(
            inv -> {
              started.countDown();
              finish.await();
              return "done";
            });
    AssistantService service = new AssistantService(chat, props(1), log);
    AtomicReference<String> first = new AtomicReference<>();
    Thread t = new Thread(() -> first.set(service.ask("slow", null)));
    t.start();
    started.await();

    assertThatThrownBy(() -> service.ask("other", null))
        .isInstanceOf(TooManyRequestsException.class);

    finish.countDown();
    t.join();
    assertThat(first.get()).isEqualTo("done");
  }

  @Test
  void interruptedWaitIsTreatedAsBusyAndKeepsInterruptFlag() {
    AssistantService service = new AssistantService(mock(ChatClient.class), props(1), log);
    Thread.currentThread().interrupt();
    try {
      assertThatThrownBy(() -> service.ask("x", null)).isInstanceOf(TooManyRequestsException.class);
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted(); // clear flag for other tests
    }
  }

  @Test
  void recordsSuccessfulRequestWithRequestId() {
    ChatClient chat = mock(ChatClient.class, RETURNS_DEEP_STUBS);
    when(chat.prompt().user("hi").call().content()).thenReturn("hello");
    MDC.put("requestId", "req-1");
    try {
      new AssistantService(chat, props(1), log).ask("hi", null);
    } finally {
      MDC.remove("requestId");
    }
    verify(log).record(eq("req-1"), eq("hi"), isNull(), eq("hello"), isNull(), anyLong());
  }

  @Test
  void sendsTheCustomerAsContextButLogsTheQuestionAsAsked() {
    ChatClient chat = mock(ChatClient.class, RETURNS_DEEP_STUBS);
    when(chat.prompt().user("Customer: acme\n\nWhat open tickets do we have?").call().content())
        .thenReturn("T-1001");
    String answer =
        new AssistantService(chat, props(1), log).ask("What open tickets do we have?", "acme");
    assertThat(answer).isEqualTo("T-1001");
    verify(log)
        .record(
            anyString(),
            eq("What open tickets do we have?"),
            eq("acme"),
            eq("T-1001"),
            isNull(),
            anyLong());
  }

  @Test
  void recordsFailedRequestWithErrorType() {
    ChatClient chat = mock(ChatClient.class, RETURNS_DEEP_STUBS);
    when(chat.prompt().user("boom").call().content()).thenThrow(new IllegalStateException("down"));
    assertThatThrownBy(() -> new AssistantService(chat, props(1), log).ask("boom", null))
        .isInstanceOf(IllegalStateException.class);
    verify(log)
        .record(
            anyString(), eq("boom"), isNull(), isNull(), eq("IllegalStateException"), anyLong());
  }

  @Test
  void rejectedRequestIsNotRecorded() {
    AssistantService service = new AssistantService(mock(ChatClient.class), props(1), log);
    Thread.currentThread().interrupt();
    try {
      assertThatThrownBy(() -> service.ask("x", null)).isInstanceOf(TooManyRequestsException.class);
    } finally {
      Thread.interrupted();
    }
    verify(log, never()).record(any(), any(), any(), any(), any(), anyLong());
  }

  @Test
  void usesConfiguredQueueWaitAndRetryAfter() throws Exception {
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch finish = new CountDownLatch(1);
    ChatClient chat = mock(ChatClient.class, RETURNS_DEEP_STUBS);
    when(chat.prompt().user("slow").call().content())
        .thenAnswer(
            inv -> {
              started.countDown();
              finish.await();
              return "done";
            });
    var props =
        new HelpdeskProperties(
            null,
            null,
            null,
            null,
            0,
            1,
            0,
            java.time.Duration.ZERO,
            java.time.Duration.ofSeconds(30),
            null);
    AssistantService service = new AssistantService(chat, props, log);
    Thread t = new Thread(() -> service.ask("slow", null)); // takes the only slot
    t.start();
    started.await();

    long start = System.nanoTime();
    assertThatThrownBy(() -> service.ask("x", null))
        .isInstanceOfSatisfying(
            TooManyRequestsException.class,
            e -> assertThat(e.retryAfter()).isEqualTo(java.time.Duration.ofSeconds(30)));
    // a zero queue wait rejects at once instead of waiting the old fixed second
    assertThat(System.nanoTime() - start).isLessThan(500_000_000L);

    finish.countDown();
    t.join();
  }
}
