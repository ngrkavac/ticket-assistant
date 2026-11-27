package com.example.assistant;

import io.modelcontextprotocol.spec.McpError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.firewall.RequestRejectedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** RFC 7807 error bodies. Never leaks stack traces or upstream error text to the caller. */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

  @ExceptionHandler(QuestionTooLongException.class)
  ProblemDetail tooLong(QuestionTooLongException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
  }

  /** The security firewall rejects a header (e.g. a control character) when MVC first reads it. */
  @ExceptionHandler(RequestRejectedException.class)
  ProblemDetail rejected(RequestRejectedException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Malformed request.");
  }

  /** A customer that is not in the caller's token (K3). */
  @ExceptionHandler(CustomerNotAllowedException.class)
  ProblemDetail customerNotAllowed(CustomerNotAllowedException e) {
    log.info("Denied access to a customer that is not in the caller's token");
    return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, e.getMessage());
  }

  /** Thrown inside MVC (the security chain renders its own 403s before MVC is reached). */
  @ExceptionHandler(AccessDeniedException.class)
  ProblemDetail denied(AccessDeniedException e) {
    log.info("Access denied: {}", e.getMessage());
    return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "Access denied.");
  }

  @ExceptionHandler(UnknownCustomerException.class)
  ProblemDetail unknownCustomer(UnknownCustomerException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
  }

  @ExceptionHandler(TicketsUnavailableException.class)
  ProblemDetail ticketsUnavailable(TicketsUnavailableException e) {
    log.warn("Ticket server unavailable: {}", e.getMessage(), e.getCause());
    return ProblemDetail.forStatusAndDetail(
        HttpStatus.SERVICE_UNAVAILABLE, "The ticket system is temporarily unavailable.");
  }

  @ExceptionHandler(TooManyRequestsException.class)
  ResponseEntity<ProblemDetail> busy(TooManyRequestsException e) {
    return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
        .header(
            HttpHeaders.RETRY_AFTER,
            String.valueOf(HelpdeskProperties.retryAfterSeconds(e.retryAfter())))
        .body(
            ProblemDetail.forStatusAndDetail(
                HttpStatus.TOO_MANY_REQUESTS, "The assistant is busy. Please retry shortly."));
  }

  /** The caller's own per-minute allowance is used up (not the assistant as a whole). */
  @ExceptionHandler(AskRateLimitedException.class)
  ResponseEntity<ProblemDetail> rateLimited(AskRateLimitedException e) {
    return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
        .header(
            HttpHeaders.RETRY_AFTER,
            String.valueOf(HelpdeskProperties.retryAfterSeconds(e.retryAfter())))
        .body(
            ProblemDetail.forStatusAndDetail(
                HttpStatus.TOO_MANY_REQUESTS,
                "You have asked too many questions in a short time. Please wait a moment."));
  }

  /** The request-log server's message is not passed on; it may describe internal limits. */
  @ExceptionHandler(RequestLogRejectedException.class)
  ProblemDetail requestLogRejected(RequestLogRejectedException e) {
    log.info("Request log rejected a request: {}", e.getMessage());
    return ProblemDetail.forStatusAndDetail(
        HttpStatus.BAD_REQUEST, "The request was rejected as invalid.");
  }

  @ExceptionHandler(RequestLogUnavailableException.class)
  ProblemDetail historyUnavailable(RequestLogUnavailableException e) {
    log.warn("Request history unavailable: {}", e.getMessage(), e.getCause());
    return ProblemDetail.forStatusAndDetail(
        HttpStatus.SERVICE_UNAVAILABLE, "Request history is temporarily unavailable.");
  }

  /** Dependencies (LLM, MCP server) failing or timing out: the caller may retry later. */
  @ExceptionHandler({
    RestClientException.class,
    TransientAiException.class,
    NonTransientAiException.class,
    ToolExecutionException.class,
    McpError.class
  })
  ProblemDetail upstream(Exception e) {
    log.error("Upstream dependency failed", e);
    return ProblemDetail.forStatusAndDetail(
        HttpStatus.SERVICE_UNAVAILABLE,
        "The assistant is temporarily unavailable. Please try again later.");
  }

  /** Anything else is a bug on our side. */
  @ExceptionHandler(Exception.class)
  ProblemDetail unexpected(Exception e) {
    log.error("Unexpected error", e);
    return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error.");
  }
}
