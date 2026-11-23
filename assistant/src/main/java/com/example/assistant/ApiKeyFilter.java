package com.example.assistant;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authentication for the {@code local} profile only (dev and demos without an identity provider).
 * With {@code helpdesk.security.api-key} set, a request carrying that key in the {@code X-API-Key}
 * header is authenticated as the local user; without it, the request stays anonymous and the
 * security chain answers 401. With a blank key every request is authenticated as the local user.
 *
 * <p>The local user has every role. It is registered only in the {@code local} security chain (see
 * {@link SecurityConfig}), never as a servlet filter of its own.
 */
class ApiKeyFilter extends OncePerRequestFilter {

  static final String HEADER = "X-API-Key";

  static final String LOCAL_USER = "local";

  static final List<String> LOCAL_ROLES =
      List.of(SecurityConfig.ROLE_AGENT, SecurityConfig.ROLE_ADMIN, SecurityConfig.ROLE_WRITER);

  private final byte[] expected;

  ApiKeyFilter(String apiKey) {
    this.expected = StringUtils.hasText(apiKey) ? apiKey.getBytes(StandardCharsets.UTF_8) : null;
  }

  /** Whether a key is required; false means every request is let in as the local user. */
  boolean keyRequired() {
    return expected != null;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (!keyRequired() || matches(request.getHeader(HEADER))) {
      var context = SecurityContextHolder.createEmptyContext();
      context.setAuthentication(localUser());
      SecurityContextHolder.setContext(context);
    }
    chain.doFilter(request, response);
  }

  private boolean matches(String provided) {
    return provided != null
        && MessageDigest.isEqual(expected, provided.getBytes(StandardCharsets.UTF_8));
  }

  static Authentication localUser() {
    return UsernamePasswordAuthenticationToken.authenticated(
        LOCAL_USER, null, AuthorityUtils.createAuthorityList(LOCAL_ROLES));
  }
}
