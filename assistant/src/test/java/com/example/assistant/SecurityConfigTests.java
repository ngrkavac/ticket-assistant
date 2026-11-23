package com.example.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The OIDC chain with real signed tokens: Boot's JWT decoder is configured with a test public key
 * (instead of an issuer's JWKS) and the audience from application.properties' default.
 */
@WebMvcTest(CustomerController.class)
@Import({SecurityConfig.class, RequestIdFilter.class, ApiExceptionHandler.class})
@EnableConfigurationProperties(HelpdeskProperties.class)
class SecurityConfigTests {

  private static final RSAKey KEY = generateKey();

  private static final RSAKey OTHER_KEY = generateKey();

  @DynamicPropertySource
  static void jwtProperties(DynamicPropertyRegistry registry) throws Exception {
    Path pem = Files.createTempFile("jwt-public", ".pem");
    pem.toFile().deleteOnExit();
    String body =
        Base64.getMimeEncoder(64, "\n".getBytes())
            .encodeToString(KEY.toRSAPublicKey().getEncoded());
    Files.writeString(pem, "-----BEGIN PUBLIC KEY-----\n" + body + "\n-----END PUBLIC KEY-----\n");
    registry.add(
        "spring.security.oauth2.resourceserver.jwt.public-key-location",
        () -> pem.toUri().toString());
    registry.add("spring.security.oauth2.resourceserver.jwt.audiences", () -> "helpdesk-assistant");
  }

  @Autowired MockMvc mvc;

  @MockitoBean TicketDirectoryClient tickets;

  @Test
  void validTokenIsAccepted() throws Exception {
    given(tickets.customers()).willReturn(List.of("acme"));
    mvc.perform(
            get("/customers")
                .header("Authorization", "Bearer " + token(KEY, "helpdesk-assistant", 300)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0]").value("acme"))
        .andExpect(header().doesNotExist("Set-Cookie"));
  }

  @Test
  void missingTokenIs401ProblemWithBearerChallenge() throws Exception {
    mvc.perform(get("/customers"))
        .andExpect(status().isUnauthorized())
        .andExpect(header().string("WWW-Authenticate", "Bearer"))
        .andExpect(content().contentType("application/problem+json"))
        .andExpect(jsonPath("$.status").value(401))
        .andExpect(jsonPath("$.title").value("Unauthorized"));
  }

  @Test
  void invalidTokensAre401() throws Exception {
    for (String token :
        List.of(
            "not-a-jwt",
            token(OTHER_KEY, "helpdesk-assistant", 300), // signed by someone else
            token(KEY, "another-app", 300), // wrong audience
            token(KEY, "helpdesk-assistant", -300))) { // expired
      mvc.perform(get("/customers").header("Authorization", "Bearer " + token))
          .andExpect(status().isUnauthorized())
          .andExpect(
              header()
                  .string(
                      "WWW-Authenticate", org.hamcrest.Matchers.containsString("invalid_token")))
          .andExpect(jsonPath("$.status").value(401));
    }
  }

  @Test
  void unknownEndpointsAreDeniedByDefault() throws Exception {
    mvc.perform(get("/admin/anything")).andExpect(status().isUnauthorized());
    mvc.perform(get("/actuator/health")).andExpect(status().isUnauthorized());
    mvc.perform(
            get("/admin/anything")
                .header("Authorization", "Bearer " + token(KEY, "helpdesk-assistant", 300)))
        .andExpect(status().isNotFound());
  }

  @Test
  void setsSecurityHeaders() throws Exception {
    mvc.perform(get("/customers").secure(true))
        .andExpect(status().isUnauthorized())
        .andExpect(header().string("X-Content-Type-Options", "nosniff"))
        .andExpect(header().string("X-Frame-Options", "DENY"))
        .andExpect(
            header().string("Content-Security-Policy", SecurityConfig.CONTENT_SECURITY_POLICY))
        .andExpect(header().string("Referrer-Policy", "no-referrer"))
        .andExpect(
            header()
                .string(
                    "Strict-Transport-Security",
                    "max-age=" + SecurityConfig.HSTS_MAX_AGE_SECONDS + " ; includeSubDomains"))
        .andExpect(
            header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
  }

  @Test
  void accessDeniedIs403Problem() throws Exception {
    var response = new MockHttpServletResponse();
    SecurityConfig.problemDenied()
        .handle(new MockHttpServletRequest(), response, new AccessDeniedException("no"));
    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(response.getContentType()).isEqualTo("application/problem+json");
    assertThat(response.getContentAsString()).isEqualTo("{\"status\":403,\"title\":\"Forbidden\"}");
  }

  @Test
  void refusesToStartWithoutAJwtDecoder() {
    var noDecoder = new StaticListableBeanFactory().getBeanProvider(JwtDecoder.class);
    assertThatThrownBy(() -> SecurityConfig.requireJwtDecoder(noDecoder))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("OIDC_ISSUER_URI");
  }

  @Test
  void actuatorIsOnlyOpenOnTheManagementServer() {
    // MockMvc requests are served by the application context, never by the management server
    assertThat(SecurityConfig.onManagementServer(new MockHttpServletRequest("GET", "/actuator")))
        .isFalse();
  }

  @Test
  void firewallRejectionsAre400() throws Exception {
    mvc.perform(get("/customers;jsessionid=x")).andExpect(status().isBadRequest());
  }

  static String token(RSAKey key, String audience, long expiresInSeconds) {
    try {
      Instant now = Instant.now();
      var claims =
          new JWTClaimsSet.Builder()
              .subject("user-1")
              .audience(audience)
              .issueTime(Date.from(now.minusSeconds(600)))
              .expirationTime(Date.from(now.plusSeconds(expiresInSeconds)))
              .build();
      var jwt =
          new SignedJWT(
              new JWSHeader.Builder(JWSAlgorithm.RS256)
                  .type(JOSEObjectType.JWT)
                  .keyID(key.getKeyID())
                  .build(),
              claims);
      jwt.sign(new RSASSASigner(key));
      return jwt.serialize();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static RSAKey generateKey() {
    try {
      return new RSAKeyGenerator(2048).keyID("test").generate();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
