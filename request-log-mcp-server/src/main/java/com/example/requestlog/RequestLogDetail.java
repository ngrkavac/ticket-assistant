package com.example.requestlog;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Instant;

/**
 * One stored request with its audit fields, as returned by {@code getRequest}.
 *
 * <p>{@code userSubject} is the caller's JWT {@code sub}. {@code toolCalls} is the JSON array
 * string sent to {@code logRequest} (normalized to compact JSON), or null. Neither is part of
 * {@link RequestLogEntry}, so {@code listRecentRequests} does not return them (contract K4).
 */
public record RequestLogDetail(
    long id,
    String requestId,
    String question,
    String answer,
    String status,
    String errorType,
    long durationMs,
    String customer,
    String userSubject,
    String toolCalls,
    @JsonFormat(shape = JsonFormat.Shape.STRING) Instant createdAt) {

  /** The same request without the audit fields. */
  RequestLogEntry toEntry() {
    return new RequestLogEntry(
        id, requestId, question, answer, status, errorType, durationMs, customer, createdAt);
  }
}
