package com.example.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class ToolCallAuditTests {

  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void noCallsIsNoJson() {
    ToolCallAudit audit = new ToolCallAudit();
    assertThat(audit.toJson()).isNull();
    assertThat(audit.calls()).isEmpty();
    assertThat(audit.dropped()).isZero();
  }

  @Test
  void writesTheK4FormatAndLeavesOutMissingFields() {
    ToolCallAudit audit = new ToolCallAudit();
    audit.add("getTicket", "acme", ToolCallAudit.SUCCESS, null);
    audit.add("createTicket", "acme", ToolCallAudit.SUCCESS, "T-1004");
    audit.add("getTicket", null, ToolCallAudit.DENIED, null);

    assertThat(audit.toJson())
        .isEqualTo(
            "[{\"tool\":\"getTicket\",\"customer\":\"acme\",\"outcome\":\"success\"},"
                + "{\"tool\":\"createTicket\",\"customer\":\"acme\",\"outcome\":\"success\","
                + "\"resultId\":\"T-1004\"},"
                + "{\"tool\":\"getTicket\",\"outcome\":\"denied\"}]");
  }

  @Test
  void keepsAtMostMaxRecordsAndCountsTheRest() {
    ToolCallAudit audit = new ToolCallAudit();
    for (int i = 0; i < ToolCallAudit.MAX_RECORDS + 5; i++) {
      audit.add("getTicket", "acme", ToolCallAudit.SUCCESS, null);
    }
    assertThat(audit.calls()).hasSize(ToolCallAudit.MAX_RECORDS);
    assertThat(audit.dropped()).isEqualTo(5);
  }

  @Test
  void shortensFieldsAndReplacesControlCharacters() {
    ToolCallAudit audit = new ToolCallAudit();
    audit.add("t\nool", "c".repeat(200), ToolCallAudit.SUCCESS, "r".repeat(200));
    ToolCallAudit.ToolCall call = audit.calls().get(0);
    assertThat(call.tool()).isEqualTo("t?ool");
    assertThat(call.customer()).hasSize(ToolCallAudit.MAX_FIELD_LENGTH);
    assertThat(call.resultId()).hasSize(ToolCallAudit.MAX_FIELD_LENGTH);
  }

  @Test
  void theJsonNeverExceedsTheRequestLogLimit() throws Exception {
    ToolCallAudit audit = new ToolCallAudit();
    String quotes = "\"".repeat(100); // each one doubles when escaped
    for (int i = 0; i < ToolCallAudit.MAX_RECORDS; i++) {
      audit.add(quotes, quotes, ToolCallAudit.ERROR, quotes);
    }
    String json = audit.toJson();
    assertThat(json.length()).isLessThanOrEqualTo(ToolCallAudit.MAX_JSON_LENGTH);
    JsonNode parsed = JSON.readTree(json);
    assertThat(parsed.isArray()).isTrue();
    assertThat(parsed.size()).isBetween(1, ToolCallAudit.MAX_RECORDS - 1);
  }
}
