package com.example.requestlog;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonFormat;

/** One /ask request handled by the assistant. */
public record RequestLogEntry(long id, String requestId, String question, String answer, String status,
		String errorType, long durationMs, @JsonFormat(shape = JsonFormat.Shape.STRING) Instant createdAt) {
}
