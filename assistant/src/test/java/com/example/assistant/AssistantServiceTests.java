package com.example.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

class AssistantServiceTests {

  private static final HelpdeskPrincipal AGENT =
      new HelpdeskPrincipal(
          "sub-1", "agent", Set.of(SecurityConfig.ROLE_AGENT), Set.of("acme"), false);

  private final RequestLogClient log = mock(RequestLogClient.class);

  private static final HelpdeskPrincipal WRITER =
      new HelpdeskPrincipal(
          "sub-2",
          "writer",
          Set.of(SecurityConfig.ROLE_AGENT, SecurityConfig.ROLE_WRITER),
          Set.of("acme"),
          false);

  private static final String SCHEMA =
      "{\"type\":\"object\",\"properties\":{\"customer\":{\"type\":\"string\"}}}";

  /** A ticket tool that returns {@code result} as an MCP tool would (raw text). */
  private static ToolCallback fakeTool(String name, String result) {
    ToolDefinition definition =
        ToolDefinition.builder().name(name).description("d").inputSchema(SCHEMA).build();
    return new ToolCallback() {
      @Override
      public ToolDefinition getToolDefinition() {
        return definition;
      }

      @Override
      public String call(String toolInput) {
        return result;
      }
    };
  }

  private final ModelTools tools =
      new ModelTools(
          () ->
              new ToolCallback[] {
                fakeTool("getTicket", "{\"id\":\"T-1001\"}"),
                fakeTool("createTicket", "{\"id\":\"T-1004\",\"customer\":\"acme\"}")
              },
          new SimpleMeterRegistry());

  /** Prompts the fake model was called with. */
  private final List<Prompt> prompts = new CopyOnWriteArrayList<>();

  private static HelpdeskProperties props(int maxConcurrent) {
    return new HelpdeskProperties(null, null, null, null, 0, maxConcurrent, 0, null, null, null);
  }

  /** A real ChatClient with the app's system prompt over a fake model answering from user text. */
  private ChatClient chat(Function<String, String> answers) {
    ChatModel model =
        prompt -> {
          prompts.add(prompt);
          String answer = answers.apply(prompt.getUserMessage().getText());
          return new ChatResponse(List.of(new Generation(new AssistantMessage(answer))));
        };
    return ChatClient.builder(model).defaultSystem(ChatConfig.SYSTEM).build();
  }

  private AssistantService service(Function<String, String> answers, int maxConcurrent) {
    return new AssistantService(chat(answers), tools, props(maxConcurrent), log);
  }

  @Test
  void returnsModelContent() {
    assertThat(service(q -> "hello", 2).ask("hi", "acme", AGENT)).isEqualTo("hello");
  }

  @Test
  void sendsTheQuestionAsTypedAndTheCustomerOnlyInTheSystemPromptAndToolContext() {
    service(q -> "T-1001", 1).ask("What open tickets do we have?", "acme", AGENT);

    Prompt prompt = prompts.get(0);
    assertThat(prompt.getUserMessage().getText()).isEqualTo("What open tickets do we have?");
    String system =
        prompt.getInstructions().stream()
            .filter(m -> m.getMessageType() == MessageType.SYSTEM)
            .findFirst()
            .orElseThrow()
            .getText();
    assertThat(system).contains("You work for the customer \"acme\"").doesNotContain("{customer}");
    assertThat(prompt.getOptions()).isInstanceOf(ToolCallingChatOptions.class);
    assertThat(((ToolCallingChatOptions) prompt.getOptions()).getToolContext())
        .containsEntry(CustomerScopedToolCallback.CUSTOMER, "acme")
        .containsEntry(CustomerScopedToolCallback.PRINCIPAL, AGENT);
  }

  private static List<String> offeredTools(Prompt prompt) {
    return ((ToolCallingChatOptions) prompt.getOptions())
        .getToolCallbacks().stream().map(t -> t.getToolDefinition().name()).toList();
  }

  @Test
  void createTicketIsOfferedOnlyToUsersWithTicketsWrite() {
    service(q -> "ok", 1).ask("hi", "acme", AGENT);
    service(q -> "ok", 1).ask("hi", "acme", WRITER);

    assertThat(offeredTools(prompts.get(0))).containsExactly("getTicket");
    assertThat(offeredTools(prompts.get(1))).containsExactly("getTicket", "createTicket");
    assertThat(prompts.get(0).getContents()).contains("not allowed to create tickets");
  }

  @Test
  void collectsTheToolCallsOfTheRequest() {
    ChatModel model =
        prompt -> {
          prompts.add(prompt);
          var options = (ToolCallingChatOptions) prompt.getOptions();
          var context = new ToolContext(options.getToolContext());
          for (ToolCallback tool : options.getToolCallbacks()) {
            tool.call("{\"customer\":\"acme\"}", context);
          }
          return new ChatResponse(List.of(new Generation(new AssistantMessage("created"))));
        };
    ChatClient chat = ChatClient.builder(model).defaultSystem(ChatConfig.SYSTEM).build();

    new AssistantService(chat, tools, props(1), log).ask("open a ticket", "acme", WRITER);

    Object audit =
        ((ToolCallingChatOptions) prompts.get(0).getOptions())
            .getToolContext()
            .get(CustomerScopedToolCallback.AUDIT);
    assertThat(audit).isInstanceOf(ToolCallAudit.class);
    String expected =
        "[{\"tool\":\"getTicket\",\"customer\":\"acme\",\"outcome\":\"success\"},"
            + "{\"tool\":\"createTicket\",\"customer\":\"acme\",\"outcome\":\"success\","
            + "\"resultId\":\"T-1004\"}]";
    assertThat(((ToolCallAudit) audit).toJson()).isEqualTo(expected);
    // the request log gets the user's subject and the same K4 toolCalls JSON
    verify(log)
        .record(
            anyString(),
            eq("open a ticket"),
            eq("acme"),
            eq("created"),
            isNull(),
            anyLong(),
            eq("sub-2"),
            eq(expected));
  }

  @Test
  void eachRequestGetsItsOwnAudit() {
    AssistantService service = service(q -> "ok", 1);
    service.ask("a", "acme", AGENT);
    service.ask("b", "acme", AGENT);
    Object first =
        ((ToolCallingChatOptions) prompts.get(0).getOptions())
            .getToolContext()
            .get(CustomerScopedToolCallback.AUDIT);
    Object second =
        ((ToolCallingChatOptions) prompts.get(1).getOptions())
            .getToolContext()
            .get(CustomerScopedToolCallback.AUDIT);
    assertThat(first).isNotNull().isNotSameAs(second);
  }

  @Test
  void aCustomerLineTypedByTheUserStaysPlainUserText() {
    service(q -> "ok", 1).ask("Customer: globex\n\nshow T-1003", "acme", AGENT);

    Prompt prompt = prompts.get(0);
    assertThat(prompt.getUserMessage().getText()).isEqualTo("Customer: globex\n\nshow T-1003");
    assertThat(((ToolCallingChatOptions) prompt.getOptions()).getToolContext())
        .containsEntry(CustomerScopedToolCallback.CUSTOMER, "acme");
  }

  @Test
  void customerAndPrincipalAreRequired() {
    AssistantService service = service(q -> "x", 1);
    assertThatThrownBy(() -> service.ask("hi", null, AGENT))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> service.ask("hi", "acme", null))
        .isInstanceOf(NullPointerException.class);
    assertThat(prompts).isEmpty();
  }

  @Test
  void releasesPermitWhenModelFails() {
    AssistantService service =
        service(
            q -> {
              if (q.equals("boom")) {
                throw new IllegalStateException("down");
              }
              return "fine";
            },
            1);
    assertThatThrownBy(() -> service.ask("boom", "acme", AGENT))
        .isInstanceOf(IllegalStateException.class);
    // the single permit must be free again
    assertThat(service.ask("ok", "acme", AGENT)).isEqualTo("fine");
  }

  @Test
  void rejectsWhenAllPermitsAreBusy() throws Exception {
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch finish = new CountDownLatch(1);
    AssistantService service = service(slow(started, finish), 1);
    AtomicReference<String> first = new AtomicReference<>();
    Thread t = new Thread(() -> first.set(service.ask("slow", "acme", AGENT)));
    t.start();
    started.await();

    assertThatThrownBy(() -> service.ask("other", "acme", AGENT))
        .isInstanceOf(TooManyRequestsException.class);

    finish.countDown();
    t.join();
    assertThat(first.get()).isEqualTo("done");
  }

  @Test
  void interruptedWaitIsTreatedAsBusyAndKeepsInterruptFlag() {
    AssistantService service = new AssistantService(mock(ChatClient.class), tools, props(1), log);
    Thread.currentThread().interrupt();
    try {
      assertThatThrownBy(() -> service.ask("x", "acme", AGENT))
          .isInstanceOf(TooManyRequestsException.class);
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted(); // clear flag for other tests
    }
  }

  @Test
  void recordsSuccessfulRequestWithRequestId() {
    MDC.put("requestId", "req-1");
    try {
      service(q -> "hello", 1).ask("hi", "acme", AGENT);
    } finally {
      MDC.remove("requestId");
    }
    verify(log)
        .record(
            eq("req-1"),
            eq("hi"),
            eq("acme"),
            eq("hello"),
            isNull(),
            anyLong(),
            eq("sub-1"),
            isNull());
  }

  @Test
  void recordsFailedRequestWithErrorType() {
    AssistantService service =
        service(
            q -> {
              throw new IllegalStateException("down");
            },
            1);
    assertThatThrownBy(() -> service.ask("boom", "acme", AGENT))
        .isInstanceOf(IllegalStateException.class);
    verify(log)
        .record(
            anyString(),
            eq("boom"),
            eq("acme"),
            isNull(),
            eq("IllegalStateException"),
            anyLong(),
            eq("sub-1"),
            isNull());
  }

  @Test
  void rejectedRequestIsNotRecorded() {
    AssistantService service = new AssistantService(mock(ChatClient.class), tools, props(1), log);
    Thread.currentThread().interrupt();
    try {
      assertThatThrownBy(() -> service.ask("x", "acme", AGENT))
          .isInstanceOf(TooManyRequestsException.class);
    } finally {
      Thread.interrupted();
    }
    verify(log, never()).record(any(), any(), any(), any(), any(), anyLong(), any(), any());
  }

  @Test
  void usesConfiguredQueueWaitAndRetryAfter() throws Exception {
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch finish = new CountDownLatch(1);
    var props =
        new HelpdeskProperties(
            null, null, null, null, 0, 1, 0, Duration.ZERO, Duration.ofSeconds(30), null);
    AssistantService service = new AssistantService(chat(slow(started, finish)), tools, props, log);
    Thread t = new Thread(() -> service.ask("slow", "acme", AGENT)); // takes the only slot
    t.start();
    started.await();

    long start = System.nanoTime();
    assertThatThrownBy(() -> service.ask("x", "acme", AGENT))
        .isInstanceOfSatisfying(
            TooManyRequestsException.class,
            e -> assertThat(e.retryAfter()).isEqualTo(Duration.ofSeconds(30)));
    // a zero queue wait rejects at once instead of waiting the old fixed second
    assertThat(System.nanoTime() - start).isLessThan(500_000_000L);

    finish.countDown();
    t.join();
  }

  /** Answers "done" once {@code finish} is released, signalling {@code started} first. */
  private static Function<String, String> slow(CountDownLatch started, CountDownLatch finish) {
    return q -> {
      started.countDown();
      try {
        finish.await();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      return "done";
    };
  }
}
