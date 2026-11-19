package com.example.assistant;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Adds a request id to the log context and the X-Request-Id response header. A valid incoming
 * X-Request-Id (e.g. from the ingress) is kept so logs can be correlated across services; anything
 * else is replaced by a random UUID. The format matches what the request-log server accepts.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class RequestIdFilter extends OncePerRequestFilter {

  static final String HEADER = "X-Request-Id";

  private static final Pattern VALID_ID = Pattern.compile("[A-Za-z0-9._:-]{1,64}");

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String id = requestId(request.getHeader(HEADER));
    MDC.put("requestId", id);
    response.setHeader(HEADER, id);
    try {
      chain.doFilter(request, response);
    } finally {
      MDC.remove("requestId");
    }
  }

  static String requestId(String incoming) {
    return incoming != null && VALID_ID.matcher(incoming).matches()
        ? incoming
        : UUID.randomUUID().toString();
  }
}
