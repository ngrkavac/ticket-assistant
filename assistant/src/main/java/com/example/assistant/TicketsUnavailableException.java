package com.example.assistant;

/** The ticket server cannot be reached or returned something unexpected. Mapped to 503. */
class TicketsUnavailableException extends RuntimeException {

  TicketsUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
