package com.example.requestlog;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Instant;

/** One /ask request handled by the assistant, optionally on behalf of a customer. */
public record RequestLogEntry(
    long id,
    String requestId,
    String question,
    String answer,
    String status,
    String errorType,
    long durationMs,
    String customer,
    @JsonFormat(shape = JsonFormat.Shape.STRING) Instant createdAt) {}
