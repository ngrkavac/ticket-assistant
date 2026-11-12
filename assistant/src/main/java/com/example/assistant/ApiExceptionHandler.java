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

	@ExceptionHandler(TooManyRequestsException.class)
	ResponseEntity<ProblemDetail> busy(TooManyRequestsException e) {
		return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
			.header(HttpHeaders.RETRY_AFTER, "5")
			.body(ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS,
					"The assistant is busy. Please retry shortly."));
	}

	/** Dependencies (LLM, MCP server) failing or timing out: the caller may retry later. */
	@ExceptionHandler({ RestClientException.class, TransientAiException.class, NonTransientAiException.class,
			ToolExecutionException.class, McpError.class })
	ProblemDetail upstream(Exception e) {
		log.error("Upstream dependency failed", e);
		return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
				"The assistant is temporarily unavailable. Please try again later.");
	}

	/** Anything else is a bug on our side. */
	@ExceptionHandler(Exception.class)
	ProblemDetail unexpected(Exception e) {
		log.error("Unexpected error", e);
		return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error.");
	}

}
