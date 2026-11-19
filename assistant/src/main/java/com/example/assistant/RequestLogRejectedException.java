package com.example.assistant;

/**
 * The request-log server rejected the arguments of a call as invalid (for example a question that
 * is too long). Mapped to 400, unlike {@link RequestLogUnavailableException}, because retrying the
 * same request will not help.
 */
class RequestLogRejectedException extends RuntimeException {

  RequestLogRejectedException(String message) {
    super(message);
  }
}
