package com.example.requestlog;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Instant;

/** A question offered in the UI's question list. */
public record Question(
    long id, String text, @JsonFormat(shape = JsonFormat.Shape.STRING) Instant createdAt) {}
