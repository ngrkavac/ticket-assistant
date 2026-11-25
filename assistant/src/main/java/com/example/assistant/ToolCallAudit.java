package com.example.assistant;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.lang.Nullable;

/**
 * The tool calls the model made during one /ask request (T1.5): which tool, for which customer,
 * with which outcome and, for a write tool, the id of what it created. {@link AssistantService}
 * puts one instance into the tool context of each request and {@link CustomerScopedToolCallback}
 * adds a record for every call, including refused ones. The records are the {@code toolCalls} of
 * contract K4; {@link #toJson()} gives them in that format.
 *
 * <p>Thread-safe. At most {@link #MAX_RECORDS} records are kept and every field is shortened, so
 * the JSON stays within the request-log server's limit even if the model loops on tool calls.
 */
final class ToolCallAudit {

  /** The tool returned a result. */
  static final String SUCCESS = "success";

  /** The tool (or the ticket server) failed, e.g. "Ticket T-1234 not found". */
  static final String ERROR = "error";

  /** The assistant refused the call; the ticket server was not called. */
  static final String DENIED = "denied";

  static final int MAX_RECORDS = 30;

  /** The request-log server's limit for {@code toolCalls} (K4). */
  static final int MAX_JSON_LENGTH = 10_000;

  static final int MAX_FIELD_LENGTH = 64;

  private static final ObjectMapper JSON = new ObjectMapper();

  /** One tool call, in the K4 {@code toolCalls} element format. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  record ToolCall(String tool, String customer, String outcome, String resultId) {}

  private final List<ToolCall> calls = new ArrayList<>();

  private final AtomicInteger dropped = new AtomicInteger();

  void add(String tool, @Nullable String customer, String outcome, @Nullable String resultId) {
    ToolCall call = new ToolCall(shorten(tool), shorten(customer), outcome, shorten(resultId));
    synchronized (calls) {
      if (calls.size() < MAX_RECORDS) {
        calls.add(call);
        return;
      }
    }
    dropped.incrementAndGet();
  }

  /** The recorded calls, oldest first. */
  List<ToolCall> calls() {
    synchronized (calls) {
      return List.copyOf(calls);
    }
  }

  /** Calls not recorded because {@link #MAX_RECORDS} was reached. */
  int dropped() {
    return dropped.get();
  }

  /**
   * The calls as a K4 {@code toolCalls} JSON array (at most {@link #MAX_JSON_LENGTH} characters),
   * or null when the model called no tool.
   */
  @Nullable
  String toJson() {
    List<ToolCall> snapshot = calls();
    if (snapshot.isEmpty()) {
      return null;
    }
    StringBuilder json = new StringBuilder("[");
    for (ToolCall call : snapshot) {
      String element = write(call);
      if (json.length() + element.length() + 2 > MAX_JSON_LENGTH) {
        break; // only with unusual (escaped) values: drop the rest rather than exceed the limit
      }
      if (json.length() > 1) {
        json.append(',');
      }
      json.append(element);
    }
    return json.append(']').toString();
  }

  private static String write(ToolCall call) {
    try {
      return JSON.writeValueAsString(call);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Cannot write a tool call record", e);
    }
  }

  @Nullable
  private static String shorten(@Nullable String value) {
    if (value == null) {
      return null;
    }
    String clean = value.replaceAll("\\p{Cntrl}", "?");
    return clean.length() > MAX_FIELD_LENGTH ? clean.substring(0, MAX_FIELD_LENGTH) : clean;
  }
}
