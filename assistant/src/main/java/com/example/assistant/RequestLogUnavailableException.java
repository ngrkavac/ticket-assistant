package com.example.assistant;

/**
 * The request log cannot be read (logging disabled, server down or bad response). Mapped to 503.
 */
class RequestLogUnavailableException extends RuntimeException {

  RequestLogUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
