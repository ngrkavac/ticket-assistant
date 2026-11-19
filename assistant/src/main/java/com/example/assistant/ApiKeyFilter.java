package com.example.assistant;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Requires header X-API-Key on the UI/API endpoints (/ask, /requests, /customers, /questions) when
 * helpdesk.security.api-key is configured.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
class ApiKeyFilter extends OncePerRequestFilter {

  private final byte[] expected;

  ApiKeyFilter(HelpdeskProperties props) {
    String key = props.security().apiKey();
    this.expected = StringUtils.hasText(key) ? key.getBytes(StandardCharsets.UTF_8) : null;
  }

  private static final List<String> PROTECTED =
      List.of("/ask", "/requests", "/customers", "/questions");

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String uri = request.getRequestURI();
    return expected == null || PROTECTED.stream().noneMatch(uri::startsWith);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String provided = request.getHeader("X-API-Key");
    boolean ok =
        provided != null
            && MessageDigest.isEqual(expected, provided.getBytes(StandardCharsets.UTF_8));
    if (!ok) {
      response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
      response.setContentType("application/problem+json");
      response.getWriter().write("{\"status\":401,\"title\":\"Unauthorized\"}");
      return;
    }
    chain.doFilter(request, response);
  }
}
