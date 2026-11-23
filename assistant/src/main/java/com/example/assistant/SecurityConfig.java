package com.example.assistant;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.firewall.HttpStatusRequestRejectedHandler;
import org.springframework.security.web.firewall.RequestRejectedHandler;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.web.context.support.WebApplicationContextUtils;

/**
 * Deny by default: every endpoint on the application port needs an authenticated caller, including
 * endpoints added later. Actuator is open only when it runs on its own management port (the
 * default, 9090), which is never published outside the cluster.
 *
 * <p>Outside the {@code local} profile the caller is an OIDC user: the assistant is an OAuth2
 * resource server that validates the bearer JWT against {@code OIDC_ISSUER_URI} and {@code
 * OIDC_AUDIENCE} (K1/K2), and refuses to start without an issuer. In the {@code local} profile the
 * optional {@code X-API-Key} check is used instead (see {@link ApiKeyFilter}).
 */
@Configuration
@EnableWebSecurity
class SecurityConfig {

  private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

  static final String ROLE_AGENT = "helpdesk-agent";

  static final String ROLE_ADMIN = "helpdesk-admin";

  static final String ROLE_WRITER = "tickets-write";

  /** The API only returns JSON: nothing may be loaded, framed or sent as a referrer. */
  static final String CONTENT_SECURITY_POLICY = "default-src 'none'; frame-ancestors 'none'";

  static final long HSTS_MAX_AGE_SECONDS = 31_536_000;

  /** Spring Boot's server namespace for the separate management (actuator) server. */
  static final String MANAGEMENT_SERVER_NAMESPACE = "management";

  @Bean
  @Order(1)
  SecurityFilterChain actuatorChain(HttpSecurity http) throws Exception {
    http.securityMatcher(SecurityConfig::onManagementServer)
        .authorizeHttpRequests(a -> a.anyRequest().permitAll());
    return stateless(http).build();
  }

  /**
   * True for requests served by the separate management server (management.server.port), which only
   * serves actuator. When actuator shares the application port, it needs a token like everything
   * else.
   */
  static boolean onManagementServer(HttpServletRequest request) {
    return WebServerApplicationContext.hasServerNamespace(
        WebApplicationContextUtils.getWebApplicationContext(request.getServletContext()),
        MANAGEMENT_SERVER_NAMESPACE);
  }

  /** Request-firewall rejections (e.g. control characters in a header) are the client's fault. */
  @Bean
  RequestRejectedHandler requestRejectedHandler() {
    return new HttpStatusRequestRejectedHandler(HttpStatus.BAD_REQUEST.value());
  }

  @Bean
  @Order(2)
  @Profile("!local")
  SecurityFilterChain oidcChain(HttpSecurity http, ObjectProvider<JwtDecoder> jwtDecoder)
      throws Exception {
    requireJwtDecoder(jwtDecoder);
    AuthenticationEntryPoint entryPoint =
        problemEntryPoint(new BearerTokenAuthenticationEntryPoint());
    api(http, entryPoint)
        .oauth2ResourceServer(o -> o.jwt(jwt -> {}).authenticationEntryPoint(entryPoint));
    return http.build();
  }

  /** Fail fast instead of starting an API that nobody can call (or a confusing bean error). */
  static void requireJwtDecoder(ObjectProvider<JwtDecoder> jwtDecoder) {
    if (jwtDecoder.getIfAvailable() == null) {
      throw new IllegalStateException(
          "No OIDC issuer configured: set OIDC_ISSUER_URI"
              + " (spring.security.oauth2.resourceserver.jwt.issuer-uri),"
              + " or run with the 'local' profile for development");
    }
  }

  @Bean
  @Order(2)
  @Profile("local")
  SecurityFilterChain localChain(HttpSecurity http, HelpdeskProperties props) throws Exception {
    ApiKeyFilter apiKey = new ApiKeyFilter(props.security().apiKey());
    if (!apiKey.keyRequired()) {
      log.warn(
          "local profile without helpdesk.security.api-key: every request is let in with all"
              + " roles. Never use the local profile in production.");
    }
    api(http, problemEntryPoint((request, response, e) -> {}))
        .addFilterBefore(apiKey, AnonymousAuthenticationFilter.class);
    return http.build();
  }

  private static HttpSecurity api(HttpSecurity http, AuthenticationEntryPoint entryPoint)
      throws Exception {
    return stateless(http)
        .authorizeHttpRequests(
            a ->
                // error dispatches render the error of a request that was already authorized
                a.dispatcherTypeMatchers(DispatcherType.ERROR)
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .exceptionHandling(
            e -> e.authenticationEntryPoint(entryPoint).accessDeniedHandler(problemDenied()))
        .headers(
            h ->
                h.contentSecurityPolicy(csp -> csp.policyDirectives(CONTENT_SECURITY_POLICY))
                    .referrerPolicy(r -> r.policy(ReferrerPolicy.NO_REFERRER))
                    .httpStrictTransportSecurity(
                        hsts ->
                            hsts.includeSubDomains(true).maxAgeInSeconds(HSTS_MAX_AGE_SECONDS)));
  }

  /** Bearer tokens only: no session, no CSRF token (nothing is authenticated by a cookie). */
  private static HttpSecurity stateless(HttpSecurity http) throws Exception {
    return http.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .csrf(c -> c.disable())
        .requestCache(r -> r.disable())
        .formLogin(f -> f.disable())
        .httpBasic(b -> b.disable())
        .logout(l -> l.disable());
  }

  /** Lets {@code delegate} set headers such as WWW-Authenticate, then writes a 401 problem. */
  static AuthenticationEntryPoint problemEntryPoint(AuthenticationEntryPoint delegate) {
    return (request, response, e) -> {
      delegate.commence(request, response, e);
      writeProblem(response, HttpStatus.UNAUTHORIZED);
    };
  }

  static AccessDeniedHandler problemDenied() {
    return (request, response, e) -> writeProblem(response, HttpStatus.FORBIDDEN);
  }

  private static void writeProblem(HttpServletResponse response, HttpStatus status)
      throws IOException {
    response.setStatus(status.value());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    response
        .getWriter()
        .write(
            "{\"status\":%d,\"title\":\"%s\"}".formatted(status.value(), status.getReasonPhrase()));
  }
}
