package com.example.requestlog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** Full context on in-memory H2 (the {@code local} profile) with the Flyway schema. */
@SpringBootTest
@ActiveProfiles("local")
class RequestLogToolsTests {

  @Autowired RequestLogTools tools;

  @Autowired ToolCallbackProvider callbacks;

  private static String newId() {
    return UUID.randomUUID().toString();
  }

  @Test
  void storesAndReadsBackARequest() {
    String id = newId();
    RequestLogEntry saved =
        tools.logRequest(
            id,
            "How do I reset my password?",
            "Use the portal.",
            "success",
            null,
            42L,
            null,
            null,
            null);

    assertThat(saved.id()).isPositive();
    assertThat(saved.status()).isEqualTo("SUCCESS");
    assertThat(saved.createdAt()).isNotNull();
    assertThat(tools.getRequest(" " + id + " ").toEntry()).isEqualTo(saved);
  }

  @Test
  void storesFailedRequestWithErrorType() {
    String id = newId();
    RequestLogEntry saved =
        tools.logRequest(id, "q", null, "ERROR", "  RestClientException ", 7L, null, null, null);
    assertThat(saved.answer()).isNull();
    assertThat(saved.errorType()).isEqualTo("RestClientException");
  }

  @Test
  void sameRequestIdIsStoredOnlyOnce() {
    String id = newId();
    RequestLogEntry first = tools.logRequest(id, "q", "a", "SUCCESS", null, 1L, null, null, null);
    RequestLogEntry retry = tools.logRequest(id, "q", "a", "SUCCESS", null, 1L, null, null, null);
    assertThat(retry.id()).isEqualTo(first.id());
  }

  @Test
  void listsNewestFirstWithLimit() {
    String older = newId();
    String newer = newId();
    tools.logRequest(older, "q1", "a", "SUCCESS", null, 1L, null, null, null);
    tools.logRequest(newer, "q2", "a", "SUCCESS", null, 1L, null, null, null);

    assertThat(tools.listRecentRequests(2, null))
        .extracting(RequestLogEntry::requestId)
        .containsExactly(newer, older);
    assertThat(tools.listRecentRequests(null, null)).hasSizeGreaterThanOrEqualTo(2);
  }

  @Test
  void rejectsInvalidInput() {
    assertThatThrownBy(
            () -> tools.logRequest(null, "q", "a", "SUCCESS", null, 1L, null, null, null))
        .hasMessageContaining("requestId is required");
    assertThatThrownBy(
            () -> tools.logRequest("bad id; drop", "q", "a", "SUCCESS", null, 1L, null, null, null))
        .hasMessageContaining("Invalid requestId");
    assertThatThrownBy(
            () -> tools.logRequest("x".repeat(65), "q", "a", "SUCCESS", null, 1L, null, null, null))
        .hasMessageContaining("Invalid requestId");
    assertThatThrownBy(
            () -> tools.logRequest(newId(), " ", "a", "SUCCESS", null, 1L, null, null, null))
        .hasMessageContaining("question is required");
    assertThatThrownBy(
            () ->
                tools.logRequest(
                    newId(), "q".repeat(10_001), "a", "SUCCESS", null, 1L, null, null, null))
        .hasMessageContaining("longer than");
    assertThatThrownBy(
            () -> tools.logRequest(newId(), "q", "a", "MAYBE", null, 1L, null, null, null))
        .hasMessageContaining("status must be");
    assertThatThrownBy(() -> tools.logRequest(newId(), "q", "a", null, null, 1L, null, null, null))
        .hasMessageContaining("status must be");
    assertThatThrownBy(
            () -> tools.logRequest(newId(), "q", "a", "SUCCESS", null, -1L, null, null, null))
        .hasMessageContaining("durationMs");
    assertThatThrownBy(
            () -> tools.logRequest(newId(), "q", "a", "SUCCESS", null, null, null, null, null))
        .hasMessageContaining("durationMs");
    assertThatThrownBy(() -> tools.listRecentRequests(0, null)).hasMessageContaining("limit");
    assertThatThrownBy(() -> tools.listRecentRequests(101, null)).hasMessageContaining("limit");
    assertThatThrownBy(() -> tools.getRequest("missing-id")).hasMessageContaining("not found");
  }

  @Test
  void storesCustomerAndFiltersByIt() {
    String acme = newId();
    String globex = newId();
    String none = newId();
    RequestLogEntry saved =
        tools.logRequest(acme, "q", "a", "SUCCESS", null, 1L, "  ACME ", null, null);
    tools.logRequest(globex, "q", "a", "SUCCESS", null, 1L, "globex", null, null);
    tools.logRequest(none, "q", "a", "SUCCESS", null, 1L, null, null, null);

    assertThat(saved.customer()).isEqualTo("acme");
    assertThat(tools.listRecentRequests(100, "Acme"))
        .extracting(RequestLogEntry::requestId)
        .contains(acme)
        .doesNotContain(globex, none);
    assertThat(tools.listRecentRequests(100, "acme")).allMatch(e -> "acme".equals(e.customer()));
    assertThat(tools.listRecentRequests(100, " "))
        .extracting(RequestLogEntry::requestId)
        .contains(acme, globex, none);
  }

  @Test
  void rejectsInvalidCustomer() {
    assertThatThrownBy(
            () -> tools.logRequest(newId(), "q", "a", "SUCCESS", null, 1L, "a b", null, null))
        .hasMessageContaining("Invalid customer");
    assertThatThrownBy(() -> tools.listRecentRequests(5, "x".repeat(65)))
        .hasMessageContaining("Invalid customer");
  }

  @Test
  void listsSeededQuestions() {
    assertThat(tools.listQuestions())
        .extracting(Question::text)
        .contains("How do I reset my password?", "List all our tickets with their status.");
  }

  @Test
  void addsQuestionOnce() {
    String text = "Is the printer on floor " + newId() + " working?";
    Question added = tools.addQuestion("  " + text + " ");
    Question again = tools.addQuestion(text);

    assertThat(added.text()).isEqualTo(text);
    assertThat(again.id()).isEqualTo(added.id());
    assertThat(tools.listQuestions()).filteredOn(q -> q.text().equals(text)).hasSize(1);
    assertThat(tools.listQuestions().getLast()).isEqualTo(added);
  }

  @Test
  void rejectsInvalidQuestion() {
    assertThatThrownBy(() -> tools.addQuestion(null)).hasMessageContaining("text is required");
    assertThatThrownBy(() -> tools.addQuestion("  ")).hasMessageContaining("text is required");
    assertThatThrownBy(() -> tools.addQuestion("q".repeat(1001)))
        .hasMessageContaining("longer than");
  }

  @Test
  void truncatesOverlongErrorType() {
    RequestLogEntry saved =
        tools.logRequest(newId(), "q", null, "ERROR", "E".repeat(300), 1L, null, null, null);
    assertThat(saved.errorType()).hasSize(200);
  }

  @Test
  void storesUserSubjectAndToolCallsAndReturnsThemOnlyFromGetRequest() {
    String id = newId();
    String calls =
        """
        [ {"tool": "getTicket", "customer": "acme", "outcome": "OK", "resultId": "T-1"},
          {"tool": "listCustomers", "outcome": "DENIED"},
          {"tool": "createTicket", "customer": null, "outcome": "ERROR", "resultId": null} ]
        """;
    RequestLogEntry saved =
        tools.logRequest(id, "q", "a", "SUCCESS", null, 1L, "acme", "  user-123 ", calls);

    RequestLogDetail detail = tools.getRequest(id);
    assertThat(detail.toEntry()).isEqualTo(saved);
    assertThat(detail.userSubject()).isEqualTo("user-123");
    assertThat(detail.toolCalls())
        .isEqualTo(
            "[{\"tool\":\"getTicket\",\"customer\":\"acme\",\"outcome\":\"OK\",\"resultId\":\"T-1\"},"
                + "{\"tool\":\"listCustomers\",\"outcome\":\"DENIED\"},"
                + "{\"tool\":\"createTicket\",\"customer\":null,\"outcome\":\"ERROR\","
                + "\"resultId\":null}]");
  }

  @Test
  void userSubjectAndToolCallsAreOptional() {
    String id = newId();
    tools.logRequest(id, "q", "a", "SUCCESS", null, 1L, null, "  ", " ");
    RequestLogDetail detail = tools.getRequest(id);
    assertThat(detail.userSubject()).isNull();
    assertThat(detail.toolCalls()).isNull();

    String empty = newId();
    tools.logRequest(empty, "q", "a", "SUCCESS", null, 1L, null, "u".repeat(255), "[]");
    assertThat(tools.getRequest(empty).toolCalls()).isEqualTo("[]");
  }

  @Test
  void rejectsInvalidUserSubjectAndToolCalls() {
    assertThatThrownBy(() -> log("u".repeat(256), null))
        .hasMessageContaining("userSubject is longer than 255");
    assertThatThrownBy(() -> log(null, "[" + " ".repeat(10_000) + "]"))
        .hasMessageContaining("toolCalls is longer than 10000");
    assertThatThrownBy(() -> log(null, "[{")).hasMessageContaining("not valid JSON");
    assertThatThrownBy(() -> log(null, "{\"tool\":\"x\"}"))
        .hasMessageContaining("must be a JSON array");
    assertThatThrownBy(() -> log(null, "[\"getTicket\"]"))
        .hasMessageContaining("toolCalls[0] must be an object");
    assertThatThrownBy(() -> log(null, "[{\"outcome\":\"OK\"}]"))
        .hasMessageContaining("toolCalls[0].tool is required");
    assertThatThrownBy(() -> log(null, "[{\"tool\":\"t\",\"outcome\":\"OK\"},{\"tool\":\"t\"}]"))
        .hasMessageContaining("toolCalls[1].outcome is required");
    assertThatThrownBy(() -> log(null, "[{\"tool\":\" \",\"outcome\":\"OK\"}]"))
        .hasMessageContaining("toolCalls[0].tool is required");
    assertThatThrownBy(() -> log(null, "[{\"tool\":\"t\",\"outcome\":\"OK\",\"customer\":1}]"))
        .hasMessageContaining("toolCalls[0].customer must be a string");
    assertThatThrownBy(
            () -> log(null, "[{\"tool\":\"t\",\"outcome\":\"OK\",\"args\":{\"text\":\"x\"}}]"))
        .hasMessageContaining("toolCalls[0] has unknown field 'args'");
    assertThatThrownBy(
            () -> log(null, "[{\"tool\":\"t\",\"outcome\":\"OK\",\"" + "k".repeat(50) + "\":1}]"))
        .hasMessageContaining("unknown field '" + "k".repeat(40) + "...'");
  }

  private RequestLogEntry log(String userSubject, String toolCalls) {
    return tools.logRequest(newId(), "q", "a", "SUCCESS", null, 1L, null, userSubject, toolCalls);
  }

  @Test
  void toolCallsAreStoredAsCompactJson() {
    String call = "{\"tool\":\"\\u0041\",\"outcome\":\"OK\"}";
    assertThat(RequestLogTools.normalizeToolCalls("[" + call + "]"))
        .isEqualTo("[{\"tool\":\"A\",\"outcome\":\"OK\"}]");
  }

  @Test
  void toolsAreExposedAndResultsSerializeToJson() {
    ToolCallback[] all = callbacks.getToolCallbacks();
    assertThat(Arrays.stream(all).map(c -> c.getToolDefinition().name()))
        .containsExactlyInAnyOrder(
            "logRequest", "getRequest", "listRecentRequests", "listQuestions", "addQuestion");

    ToolCallback log =
        Arrays.stream(all)
            .filter(c -> c.getToolDefinition().name().equals("logRequest"))
            .findFirst()
            .orElseThrow();
    String id = newId();
    String json =
        log.call(
            """
            {"requestId":"%s","question":"VPN drops","answer":"Switch to TCP","status":"SUCCESS","durationMs":12}
            """
                .formatted(id));
    assertThat(json)
        .contains(id)
        .contains("\"status\":\"SUCCESS\"")
        .containsPattern("\"createdAt\":\"\\d{4}-\\d{2}-\\d{2}T");
    assertThat(json).doesNotContain("userSubject", "toolCalls");

    log.call(
        """
        {"requestId":"%s","question":"q","status":"SUCCESS","durationMs":1,
         "userSubject":"user-9","toolCalls":"[{\\"tool\\":\\"getTicket\\",\\"outcome\\":\\"OK\\"}]"}
        """
            .formatted(id + "-k4"));
    assertThat(callTool(all, "getRequest", "{\"requestId\":\"%s-k4\"}".formatted(id)))
        .contains("\"userSubject\":\"user-9\"")
        .contains("\"toolCalls\":\"[{\\\"tool\\\":\\\"getTicket\\\"");
    assertThat(callTool(all, "listRecentRequests", "{\"limit\":100}"))
        .contains(id + "-k4")
        .doesNotContain("userSubject", "toolCalls");
  }

  private static String callTool(ToolCallback[] all, String name, String input) {
    return Arrays.stream(all)
        .filter(c -> c.getToolDefinition().name().equals(name))
        .findFirst()
        .orElseThrow()
        .call(input);
  }
}
