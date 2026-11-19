package com.example.requestlog;

import java.util.List;
import java.util.Locale;
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
          String customer) {
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
    return repository.save(
        id, question, answer, normalizedStatus, error, durationMs, normalizeCustomer(customer));
  }

  @Tool(description = "Get one stored assistant request by its request id.")
  public RequestLogEntry getRequest(@ToolParam(description = "Request id") String requestId) {
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
