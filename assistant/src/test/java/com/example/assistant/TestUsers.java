package com.example.assistant;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;

import java.util.Set;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Signed-in users for MockMvc tests, authenticated the way the OIDC chain does it: a {@link
 * HelpdeskJwtConverter.Authentication} whose principal is a {@link HelpdeskPrincipal} and whose
 * authorities are its roles. (Spring Security's {@code jwt()} post-processor does not run the
 * converter, so controllers would not see a {@link HelpdeskPrincipal}.)
 */
final class TestUsers {

  private TestUsers() {}

  /** {@code helpdesk-agent} for the given customers. */
  static RequestPostProcessor agent(String... customers) {
    return user(Set.of(SecurityConfig.ROLE_AGENT), Set.of(customers), false);
  }

  /**
   * {@code helpdesk-agent} and {@code helpdesk-admin} for every customer, like the realm's admin.
   */
  static RequestPostProcessor admin() {
    return user(Set.of(SecurityConfig.ROLE_AGENT, SecurityConfig.ROLE_ADMIN), Set.of(), true);
  }

  static RequestPostProcessor user(Set<String> roles, Set<String> customers, boolean all) {
    var principal = new HelpdeskPrincipal("user-1", "user", roles, customers, all);
    var jwt = Jwt.withTokenValue("token").header("alg", "none").subject("user-1").build();
    var authorities =
        roles.stream().sorted().<GrantedAuthority>map(SimpleGrantedAuthority::new).toList();
    return authentication(new HelpdeskJwtConverter.Authentication(jwt, principal, authorities));
  }
}
