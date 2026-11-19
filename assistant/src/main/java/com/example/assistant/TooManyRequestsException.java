package com.example.assistant;

import java.time.Duration;

/** All request permits are busy. Mapped to 429 with a Retry-After header. */
class TooManyRequestsException extends RuntimeException {

  private final Duration retryAfter;

  TooManyRequestsException() {
    this(Duration.ofSeconds(5));
  }

  TooManyRequestsException(Duration retryAfter) {
    super("Too many concurrent requests");
    this.retryAfter = retryAfter;
  }

  Duration retryAfter() {
    return retryAfter;
  }
}
