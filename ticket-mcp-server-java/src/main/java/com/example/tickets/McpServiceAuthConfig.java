package com.example.tickets;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Service-to-service authentication for the MCP endpoints (contract K5).
 *
 * <p>Every request except {@code /actuator/**} (probes) must carry {@code Authorization: Bearer
 * <MCP_SERVICE_TOKEN>}, otherwise it gets 401. A request with an {@code Origin} header that is not
 * listed in {@code MCP_ALLOWED_ORIGINS} (comma-separated, empty by default) gets 403, so a browser
 * page cannot call the server (DNS rebinding); server-to-server clients send no {@code Origin}.
 *
 * <p>A blank token disables the token check (not the Origin check) and logs a warning. The {@code
 * prod} profile requires a token: {@link ProdConfigGuard} fails startup without one.
 */
@Configuration(proxyBeanMethods = false)
class McpServiceAuthConfig {

  static final String TOKEN_PROPERTY = "mcp.service-token";

  static final String ORIGINS_PROPERTY = "mcp.allowed-origins";

  private static final Logger log = LoggerFactory.getLogger(McpServiceAuthConfig.class);

  @Bean
  FilterRegistrationBean<ServiceAuthFilter> mcpServiceAuthFilter(
      @Value("${" + TOKEN_PROPERTY + ":}") String token,
      @Value("${" + ORIGINS_PROPERTY + ":}") String allowedOrigins) {
    if (token.isBlank()) {
      // Never reached in the prod profile: ProdConfigGuard fails startup without a token first.
      log.warn("MCP_SERVICE_TOKEN is not set: MCP requests are accepted without a token");
    }
    var registration =
        new FilterRegistrationBean<>(new ServiceAuthFilter(token, parseOrigins(allowedOrigins)));
    // Right after Spring's ServerHttpObservationFilter (HIGHEST_PRECEDENCE + 1), so rejected
    // requests (401/403) still show up in http_server_requests_seconds and in traces.
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 2);
    return registration;
  }

  static Set<String> parseOrigins(String allowedOrigins) {
    return Arrays.stream(allowedOrigins.split(","))
        .map(String::strip)
        .filter(origin -> !origin.isEmpty())
        .collect(Collectors.toUnmodifiableSet());
  }

  /** Checks the Origin header and the bearer token; see {@link McpServiceAuthConfig}. */
  static final class ServiceAuthFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";

    private final byte[] token;

    private final Set<String> allowedOrigins;

    ServiceAuthFilter(String token, Set<String> allowedOrigins) {
      this.token = token.isBlank() ? null : token.getBytes(StandardCharsets.UTF_8);
      this.allowedOrigins = allowedOrigins;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
      String path = request.getRequestURI().substring(request.getContextPath().length());
      return path.equals("/actuator") || path.startsWith("/actuator/");
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
      String origin = request.getHeader(HttpHeaders.ORIGIN);
      if (origin != null && !allowedOrigins.contains(origin)) {
        response.sendError(HttpServletResponse.SC_FORBIDDEN, "Origin not allowed");
        return;
      }
      if (token != null && !hasToken(request.getHeader(HttpHeaders.AUTHORIZATION))) {
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Missing or invalid service token");
        return;
      }
      chain.doFilter(request, response);
    }

    private boolean hasToken(String authorization) {
      if (authorization == null
          || !authorization.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
        return false;
      }
      byte[] presented =
          authorization.substring(BEARER.length()).strip().getBytes(StandardCharsets.UTF_8);
      // Constant-time comparison, so the response time does not reveal how much of it matched.
      return MessageDigest.isEqual(presented, token);
    }
  }
}
