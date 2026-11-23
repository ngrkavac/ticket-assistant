package com.example.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

class JwkSetUriDecoderTests {

  private static final String ISSUER = "http://localhost:8180/realms/helpdesk";

  private final SecurityConfig config = new SecurityConfig();

  private static Jwt jwt(String issuer, List<String> audience, Instant expiresAt) {
    return Jwt.withTokenValue("t")
        .header("alg", "RS256")
        .subject("u-1")
        .issuer(issuer)
        .audience(audience)
        .issuedAt(expiresAt.minusSeconds(600))
        .expiresAt(expiresAt)
        .build();
  }

  @Test
  void validatesIssuerAudienceAndExpiry() {
    var validator = SecurityConfig.jwtValidator(ISSUER, Set.of(" helpdesk-assistant", ""));
    Instant later = Instant.now().plusSeconds(300);
    assertThat(validator.validate(jwt(ISSUER, List.of("helpdesk-assistant"), later)).hasErrors())
        .isFalse();
    assertThat(
            validator
                .validate(jwt(ISSUER, List.of("account", "helpdesk-assistant"), later))
                .hasErrors())
        .isFalse();
    assertThat(
            validator
                .validate(jwt("http://evil", List.of("helpdesk-assistant"), later))
                .hasErrors())
        .isTrue();
    assertThat(validator.validate(jwt(ISSUER, List.of("other"), later)).hasErrors()).isTrue();
    assertThat(
            validator
                .validate(
                    jwt(ISSUER, List.of("helpdesk-assistant"), Instant.now().minusSeconds(600)))
                .hasErrors())
        .isTrue();
  }

  @Test
  void withoutConfiguredAudienceAnyAudienceIsAccepted() {
    var validator = SecurityConfig.jwtValidator(ISSUER, Set.of());
    assertThat(
            validator
                .validate(jwt(ISSUER, List.of("x"), Instant.now().plusSeconds(300)))
                .hasErrors())
        .isFalse();
  }

  @Test
  void buildsADecoderWithoutContactingTheProvider() {
    assertThat(config.jwkSetUriJwtDecoder("http://localhost:1/certs", ISSUER, "helpdesk-assistant"))
        .isInstanceOf(NimbusJwtDecoder.class);
  }

  @Test
  void needsAnIssuer() {
    assertThatThrownBy(() -> config.jwkSetUriJwtDecoder("http://localhost:1/certs", " ", "a"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("OIDC_ISSUER_URI");
  }
}
