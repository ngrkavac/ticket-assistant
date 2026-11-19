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

/** Full context on in-memory H2 with the Flyway schema. */
@SpringBootTest
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
            id, "How do I reset my password?", "Use the portal.", "success", null, 42L, null);

    assertThat(saved.id()).isPositive();
    assertThat(saved.status()).isEqualTo("SUCCESS");
    assertThat(saved.createdAt()).isNotNull();
    assertThat(tools.getRequest(" " + id + " ")).isEqualTo(saved);
  }

  @Test
  void storesFailedRequestWithErrorType() {
    String id = newId();
    RequestLogEntry saved =
        tools.logRequest(id, "q", null, "ERROR", "  RestClientException ", 7L, null);
    assertThat(saved.answer()).isNull();
    assertThat(saved.errorType()).isEqualTo("RestClientException");
  }

  @Test
  void sameRequestIdIsStoredOnlyOnce() {
    String id = newId();
    RequestLogEntry first = tools.logRequest(id, "q", "a", "SUCCESS", null, 1L, null);
    RequestLogEntry retry = tools.logRequest(id, "q", "a", "SUCCESS", null, 1L, null);
    assertThat(retry.id()).isEqualTo(first.id());
  }

  @Test
  void listsNewestFirstWithLimit() {
    String older = newId();
    String newer = newId();
    tools.logRequest(older, "q1", "a", "SUCCESS", null, 1L, null);
    tools.logRequest(newer, "q2", "a", "SUCCESS", null, 1L, null);

    assertThat(tools.listRecentRequests(2, null))
        .extracting(RequestLogEntry::requestId)
        .containsExactly(newer, older);
    assertThat(tools.listRecentRequests(null, null)).hasSizeGreaterThanOrEqualTo(2);
  }

  @Test
  void rejectsInvalidInput() {
    assertThatThrownBy(() -> tools.logRequest(null, "q", "a", "SUCCESS", null, 1L, null))
        .hasMessageContaining("requestId is required");
    assertThatThrownBy(() -> tools.logRequest("bad id; drop", "q", "a", "SUCCESS", null, 1L, null))
        .hasMessageContaining("Invalid requestId");
    assertThatThrownBy(() -> tools.logRequest("x".repeat(65), "q", "a", "SUCCESS", null, 1L, null))
        .hasMessageContaining("Invalid requestId");
    assertThatThrownBy(() -> tools.logRequest(newId(), " ", "a", "SUCCESS", null, 1L, null))
        .hasMessageContaining("question is required");
    assertThatThrownBy(
            () -> tools.logRequest(newId(), "q".repeat(10_001), "a", "SUCCESS", null, 1L, null))
        .hasMessageContaining("longer than");
    assertThatThrownBy(() -> tools.logRequest(newId(), "q", "a", "MAYBE", null, 1L, null))
        .hasMessageContaining("status must be");
    assertThatThrownBy(() -> tools.logRequest(newId(), "q", "a", null, null, 1L, null))
        .hasMessageContaining("status must be");
    assertThatThrownBy(() -> tools.logRequest(newId(), "q", "a", "SUCCESS", null, -1L, null))
        .hasMessageContaining("durationMs");
    assertThatThrownBy(() -> tools.logRequest(newId(), "q", "a", "SUCCESS", null, null, null))
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
    RequestLogEntry saved = tools.logRequest(acme, "q", "a", "SUCCESS", null, 1L, "  ACME ");
    tools.logRequest(globex, "q", "a", "SUCCESS", null, 1L, "globex");
    tools.logRequest(none, "q", "a", "SUCCESS", null, 1L, null);

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
    assertThatThrownBy(() -> tools.logRequest(newId(), "q", "a", "SUCCESS", null, 1L, "a b"))
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
        tools.logRequest(newId(), "q", null, "ERROR", "E".repeat(300), 1L, null);
    assertThat(saved.errorType()).hasSize(200);
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
  }
}
