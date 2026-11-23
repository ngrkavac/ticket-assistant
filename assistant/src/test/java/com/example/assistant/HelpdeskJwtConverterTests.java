package com.example.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.AbstractOAuth2TokenAuthenticationToken;

class HelpdeskJwtConverterTests {

  private final HelpdeskJwtConverter converter =
      new HelpdeskJwtConverter(new HelpdeskProperties.Security(null, null, null));

  private static Jwt jwt(Consumer<Map<String, Object>> claims) {
    return Jwt.withTokenValue("t")
        .header("alg", "RS256")
        .issuedAt(Instant.now())
        .expiresAt(Instant.now().plusSeconds(60))
        .claims(claims)
        .build();
  }

  @Test
  void mapsRolesToAuthoritiesAndCustomersToTheAllowedSet() {
    var auth =
        converter.convert(
            jwt(
                c -> {
                  c.put("sub", "u-1");
                  c.put("preferred_username", "acme-agent");
                  c.put("roles", List.of("helpdesk-agent", "tickets-write"));
                  c.put("customers", List.of("Acme", " globex "));
                }));
    assertThat(auth.isAuthenticated()).isTrue();
    assertThat(auth.getName()).isEqualTo("u-1");
    assertThat(auth.getAuthorities())
        .extracting(GrantedAuthority::getAuthority)
        .containsExactly("helpdesk-agent", "tickets-write");
    assertThat(((AbstractOAuth2TokenAuthenticationToken<?>) auth).getTokenAttributes())
        .containsEntry("sub", "u-1");
    var p = HelpdeskPrincipal.of(auth);
    assertThat(p.subject()).isEqualTo("u-1");
    assertThat(p.username()).isEqualTo("acme-agent");
    assertThat(p.customers()).containsExactlyInAnyOrder("acme", "globex");
    assertThat(p.allCustomers()).isFalse();
    assertThat(p.hasRole("tickets-write")).isTrue();
    assertThat(p.hasRole("helpdesk-admin")).isFalse();
    assertThat(p.canAccess("ACME ")).isTrue();
    assertThat(p.canAccess("umbrella")).isFalse();
    assertThat(p.canAccess(null)).isFalse();
  }

  @Test
  void starMeansEveryCustomer() {
    var p =
        converter.principal(
            jwt(
                c -> {
                  c.put("sub", "admin-1");
                  c.put("roles", List.of("helpdesk-admin"));
                  c.put("customers", List.of("*"));
                }));
    assertThat(p.allCustomers()).isTrue();
    assertThat(p.customers()).isEmpty();
    assertThat(p.canAccess("anything")).isTrue();
    assertThat(p.username()).isEqualTo("admin-1"); // no preferred_username: falls back to sub
  }

  @Test
  void missingClaimsMeanNoRolesAndNoCustomers() {
    var auth = converter.convert(jwt(c -> c.put("sub", "u-2")));
    var p = HelpdeskPrincipal.of(auth);
    assertThat(auth.getAuthorities()).isEmpty();
    assertThat(p.roles()).isEmpty();
    assertThat(p.customers()).isEmpty();
    assertThat(p.canAccess("acme")).isFalse();
  }

  @Test
  void ignoresBlankAndNonStringEntriesAndAcceptsSeparatedStrings() {
    var p =
        converter.principal(
            jwt(
                c -> {
                  c.put("sub", "u-3");
                  c.put("roles", "helpdesk-agent, tickets-write");
                  c.put("customers", java.util.Arrays.asList("acme", " ", 42, null));
                }));
    assertThat(p.roles()).containsExactlyInAnyOrder("helpdesk-agent", "tickets-write");
    assertThat(p.customers()).containsExactly("acme");
  }

  @Test
  void claimNamesAreConfigurableAndMayBeNested() {
    var custom =
        new HelpdeskJwtConverter(
            new HelpdeskProperties.Security(null, "realm_access.roles", "tenants"));
    var p =
        custom.principal(
            jwt(
                c -> {
                  c.put("sub", "u-4");
                  c.put("realm_access", Map.of("roles", List.of("helpdesk-agent")));
                  c.put("tenants", List.of("globex"));
                  c.put("roles", List.of("helpdesk-admin")); // not the configured claim
                }));
    assertThat(p.roles()).containsExactly("helpdesk-agent");
    assertThat(p.customers()).containsExactly("globex");
  }

  @Test
  void nestedPathThroughANonObjectIsMissing() {
    assertThat(HelpdeskJwtConverter.values(Map.of("realm_access", "x"), "realm_access.roles"))
        .isEmpty();
    assertThat(HelpdeskJwtConverter.values(Map.of(), "a.b")).isEmpty();
  }

  @Test
  void tokenWithoutSubjectIsRejected() {
    assertThatThrownBy(() -> converter.convert(jwt(c -> c.put("roles", List.of("x")))))
        .isInstanceOf(InvalidBearerTokenException.class);
  }

  @Test
  void localPrincipalHasEveryRoleAndCustomer() {
    var p = HelpdeskPrincipal.LOCAL;
    assertThat(p.getName()).isEqualTo("local");
    assertThat(p.roles())
        .containsExactlyInAnyOrder("helpdesk-agent", "helpdesk-admin", "tickets-write");
    assertThat(p.canAccess("acme")).isTrue();
    assertThat(HelpdeskPrincipal.of(ApiKeyFilter.localUser())).isSameAs(p);
    assertThat(HelpdeskPrincipal.of(null)).isNull();
  }
}
