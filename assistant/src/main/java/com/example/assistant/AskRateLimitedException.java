package com.example.assistant;

import java.time.Duration;

/** The user has used up their asks for this minute. Mapped to 429 with a Retry-After header. */
class AskRateLimitedException extends RuntimeException {

  private final Duration retryAfter;

  AskRateLimitedException(Duration retryAfter) {
    super("Per-user rate limit reached");
    this.retryAfter = retryAfter;
  }

  Duration retryAfter() {
    return retryAfter;
  }
}
