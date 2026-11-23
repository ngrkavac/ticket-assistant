package com.example.requestlog;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

/** Tools exposed over MCP: store and read assistant requests, and the UI's question list. */
@Service
public class RequestLogTools {

  static final int MAX_REQUEST_ID_LENGTH = 64;

  static final int MAX_QUESTION_LENGTH = 10_000;

  static final int MAX_ERROR_TYPE_LENGTH = 200;

  static final int MAX_CATALOG_QUESTION_LENGTH = 1000;

  static final int MAX_USER_SUBJECT_LENGTH = 255;

  static final int MAX_TOOL_CALLS_LENGTH = 10_000;

  /** Fields of one {@code toolCalls} element (K4); {@code true} marks a required one. */
  private static final Map<String, Boolean> TOOL_CALL_FIELDS =
      Map.of("tool", true, "customer", false, "outcome", true, "resultId", false);

  private static final ObjectMapper JSON = new ObjectMapper();

  static final int DEFAULT_LIMIT = 20;

  static final int MAX_LIMIT = 100;

  private static final Set<String> STATUSES = Set.of("SUCCESS", "ERROR");

  private final RequestLogRepository repository;

  private final QuestionRepository questions;

  public RequestLogTools(RequestLogRepository repository, QuestionRepository questions) {
    this.repository = repository;
    this.questions = questions;
  }

  @Tool(
      description =
          "Store one assistant request (question, answer, outcome). Idempotent per requestId.")
  public RequestLogEntry logRequest(
      @ToolParam(description = "Unique request id, e.g. a UUID") String requestId,
      @ToolParam(description = "The user's question") String question,
      @ToolParam(
              description = "The assistant's answer; empty if the request failed",
              required = false)
          String answer,
      @ToolParam(description = "SUCCESS or ERROR") String status,
      @ToolParam(description = "Error class name when status is ERROR", required = false)
          String errorType,
      @ToolParam(description = "Time spent handling the request in milliseconds") Long durationMs,
      @ToolParam(description = "Customer the request was asked for, e.g. acme", required = false)
          String customer,
      @ToolParam(
              description = "Subject (JWT sub) of the user who asked, at most 255 characters",
              required = false)
          String userSubject,
      @ToolParam(
              description =
                  "Tools called for this request, as a JSON array string of"
                      + " {tool, customer, outcome, resultId?}; at most 10000 characters",
              required = false)
          String toolCalls) {
    String id = requireRequestId(requestId);
    if (question == null || question.isBlank()) {
      throw new IllegalArgumentException("question is required");
    }
    if (question.length() > MAX_QUESTION_LENGTH) {
      throw new IllegalArgumentException(
          "question is longer than " + MAX_QUESTION_LENGTH + " characters");
    }
    String normalizedStatus = status == null ? "" : status.trim().toUpperCase();
    if (!STATUSES.contains(normalizedStatus)) {
      throw new IllegalArgumentException("status must be one of " + STATUSES);
    }
    if (durationMs == null || durationMs < 0) {
      throw new IllegalArgumentException("durationMs must be zero or positive");
    }
    String error = errorType == null || errorType.isBlank() ? null : errorType.trim();
    if (error != null && error.length() > MAX_ERROR_TYPE_LENGTH) {
      error = error.substring(0, MAX_ERROR_TYPE_LENGTH);
    }
    return repository
        .save(
            id,
            question,
            answer,
            normalizedStatus,
            error,
            durationMs,
            normalizeCustomer(customer),
            normalizeUserSubject(userSubject),
            normalizeToolCalls(toolCalls))
        .toEntry();
  }

  @Tool(
      description =
          "Get one stored assistant request by its request id, including the user subject and the"
              + " tool calls.")
  public RequestLogDetail getRequest(@ToolParam(description = "Request id") String requestId) {
    String id = requireRequestId(requestId);
    return repository
        .findByRequestId(id)
        .orElseThrow(() -> new IllegalArgumentException("Request " + id + " not found"));
  }

  @Tool(
      description =
          "List the most recent assistant requests, newest first, optionally only for one"
              + " customer.")
  public List<RequestLogEntry> listRecentRequests(
      @ToolParam(description = "How many to return, 1-100 (default 20)", required = false)
          Integer limit,
      @ToolParam(description = "Only requests for this customer, e.g. acme", required = false)
          String customer) {
    int n = limit == null ? DEFAULT_LIMIT : limit;
    if (n < 1 || n > MAX_LIMIT) {
      throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
    }
    return repository.findRecent(n, normalizeCustomer(customer));
  }

  @Tool(description = "List the questions offered in the UI, oldest first.")
  public List<Question> listQuestions() {
    return questions.findAll();
  }

  @Tool(
      description =
          "Add a question to the list offered in the UI. Idempotent: an existing question with the"
              + " same text is returned unchanged.")
  public Question addQuestion(@ToolParam(description = "The question text") String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("text is required");
    }
    String t = text.trim();
    if (t.length() > MAX_CATALOG_QUESTION_LENGTH) {
      throw new IllegalArgumentException(
          "text is longer than " + MAX_CATALOG_QUESTION_LENGTH + " characters");
    }
    return questions.save(t);
  }

  /** Null when blank; otherwise trimmed and at most {@value #MAX_USER_SUBJECT_LENGTH} chars. */
  static String normalizeUserSubject(String userSubject) {
    if (userSubject == null || userSubject.isBlank()) {
      return null;
    }
    String s = userSubject.trim();
    if (s.length() > MAX_USER_SUBJECT_LENGTH) {
      throw new IllegalArgumentException(
          "userSubject is longer than " + MAX_USER_SUBJECT_LENGTH + " characters");
    }
    return s;
  }

  /**
   * Null when blank; otherwise checked to be a JSON array of {@code {tool, customer, outcome,
   * resultId?}} objects and returned as compact JSON. Unknown fields are rejected, so only the
   * audit fields of contract K4 are stored.
   */
  static String normalizeToolCalls(String toolCalls) {
    if (toolCalls == null || toolCalls.isBlank()) {
      return null;
    }
    if (toolCalls.length() > MAX_TOOL_CALLS_LENGTH) {
      throw new IllegalArgumentException(
          "toolCalls is longer than " + MAX_TOOL_CALLS_LENGTH + " characters");
    }
    JsonNode calls;
    try {
      calls = JSON.readTree(toolCalls);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("toolCalls is not valid JSON");
    }
    if (calls == null || !calls.isArray()) {
      throw new IllegalArgumentException("toolCalls must be a JSON array");
    }
    for (int i = 0; i < calls.size(); i++) {
      checkToolCall(calls.get(i), i);
    }
    // Compact JSON is never longer than the (valid) input, so the length limit still holds.
    return calls.toString();
  }

  private static void checkToolCall(JsonNode call, int index) {
    String where = "toolCalls[" + index + "]";
    if (!call.isObject()) {
      throw new IllegalArgumentException(where + " must be an object");
    }
    for (Iterator<String> names = call.fieldNames(); names.hasNext(); ) {
      String name = names.next();
      if (!TOOL_CALL_FIELDS.containsKey(name)) {
        throw new IllegalArgumentException(where + " has unknown field " + quote(name));
      }
    }
    TOOL_CALL_FIELDS.forEach(
        (name, required) -> {
          JsonNode value = call.get(name);
          boolean missing = value == null || value.isNull();
          if (missing && required) {
            throw new IllegalArgumentException(where + "." + name + " is required");
          }
          if (!missing && !value.isTextual()) {
            throw new IllegalArgumentException(where + "." + name + " must be a string");
          }
          if (!missing && required && value.asText().isBlank()) {
            throw new IllegalArgumentException(where + "." + name + " is required");
          }
        });
  }

  /** A field name for an error message: short and without control characters. */
  private static String quote(String name) {
    String safe = name.replaceAll("\\p{Cntrl}", "?");
    return "'" + (safe.length() > 40 ? safe.substring(0, 40) + "..." : safe) + "'";
  }

  /** Null for no customer; otherwise trimmed, lower-cased and checked. */
  private static String normalizeCustomer(String customer) {
    if (customer == null || customer.isBlank()) {
      return null;
    }
    String c = customer.trim().toLowerCase(Locale.ROOT);
    if (!c.matches("[a-z0-9-]{1,64}")) {
      throw new IllegalArgumentException("Invalid customer");
    }
    return c;
  }

  private static String requireRequestId(String requestId) {
    if (requestId == null || requestId.isBlank()) {
      throw new IllegalArgumentException("requestId is required");
    }
    String id = requestId.trim();
    if (id.length() > MAX_REQUEST_ID_LENGTH || !id.matches("[A-Za-z0-9._:-]+")) {
      throw new IllegalArgumentException("Invalid requestId");
    }
    return id;
  }
}
